package com.eberhard.iphotos.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eberhard.iphotos.data.BridgeException
import com.eberhard.iphotos.data.PhotosApplication
import com.eberhard.iphotos.data.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Scheduling. Three ways a backup run starts: every 15 minutes (the periodic job, which also
 * covers "Tailscale came back" because a run that can't reach the Mac retries with backoff),
 * when the camera roll changes (a content-URI trigger that re-arms itself), and by hand.
 * The network and charging constraints follow the settings.
 */
object Backup {
    const val PERIODIC = "backup-periodic"
    const val TRIGGER = "backup-on-change"
    const val NOW = "backup-now"

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress.asStateFlow()
    internal fun report(running: Boolean, text: String = "") { _running.value = running; _progress.value = text }

    private fun constraints(context: Context): Constraints {
        val prefs = PhotosApplication.of(context).prefs
        return Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(prefs.chargingOnly)
            .build()
    }

    /** (Re)applies the schedule from the settings; safe to call often. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val prefs = PhotosApplication.of(context).prefs
        if (!prefs.autoBackup) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(TRIGGER)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(context))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        armTrigger(context)
    }

    /** Runs soon after a new photo or video lands in the camera roll. One-shot: re-armed after each run. */
    fun armTrigger(context: Context) {
        val prefs = PhotosApplication.of(context).prefs
        if (!prefs.autoBackup) return
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(prefs.chargingOnly)
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(20, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(TRIGGER, ExistingWorkPolicy.KEEP, req)
    }

    /** "Sync now" and the share sheet: ignore the schedule's constraints, try at once. */
    fun now(context: Context) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }
}

/** One backup run: scan for new items, then upload every pending item, oldest first. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = PhotosApplication.of(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = app.prefs
        val ledger = app.ledger
        val bridge = app.bridge
        try {
            Backup.report(true, "Looking for new photos…")
            if (prefs.autoBackup) {
                runCatching { ledger.addPending(Scanner.newItems(applicationContext, ledger, prefs.sinceMs, prefs.cameraOnly)) }
            }
            val pending = ledger.pending()
            if (pending.isEmpty()) {
                prefs.lastRunMs = System.currentTimeMillis()
                prefs.lastRunNote = "Nothing new"
                return@withContext Result.success()
            }
            if (!prefs.isConfigured && !bridge.borrowFromIMessage()) {
                prefs.lastRunNote = "No bridge connection: open the iMessage app once"
                return@withContext Result.retry()
            }
            runCatching { setForeground(foregroundInfo(pending.size)) }
            var sent = 0
            var failed = 0
            for ((i, item) in pending.withIndex()) {
                Backup.report(true, "Uploading ${i + 1} of ${pending.size}: ${item.name}")
                runCatching { setForeground(foregroundInfo(pending.size - i, item.name)) }
                val uri = Uri.parse(item.uri)
                val sha = try {
                    bridge.sha256(uri)
                } catch (e: Exception) {
                    // Gone from the phone (deleted before it was sent): nothing to back up.
                    ledger.setState(item.id, "skipped", "File no longer on the phone")
                    continue
                }
                try {
                    val status = bridge.upload(uri, item.name, item.size, sha)
                    ledger.setState(item.id, "done", status, sha)
                    sent++
                    if (item.source == "share") runCatching { java.io.File(uri.path.orEmpty()).delete() }
                } catch (e: BridgeException.Unreachable) {
                    prefs.lastRunMs = System.currentTimeMillis()
                    prefs.lastRunNote = if (bridge.tailscaleUp()) "The Mac didn't answer; will try again" else "Waiting for Tailscale; ${pending.size - sent} to send"
                    return@withContext Result.retry()
                } catch (e: BridgeException.Unauthorized) {
                    prefs.lastRunNote = "The Mac rejected the token: open the iMessage app, then Sync now"
                    prefs.token = ""
                    return@withContext Result.retry()
                } catch (e: BridgeException.Server) {
                    failed++
                    ledger.setState(item.id, "failed", e.message ?: e.code)
                    if (e.code == "photos_import") {
                        // Photos.app itself refused (permission prompt on the Mac, etc.): stop the run,
                        // the rest stays pending.
                        prefs.lastRunMs = System.currentTimeMillis()
                        prefs.lastRunNote = e.message ?: "Photos on the Mac refused the import"
                        return@withContext Result.retry()
                    }
                } catch (e: Exception) {
                    failed++
                    ledger.setState(item.id, "failed", e.message ?: e.javaClass.simpleName)
                }
            }
            prefs.lastRunMs = System.currentTimeMillis()
            prefs.lastRunNote = "Sent $sent" + if (failed > 0) ", $failed failed" else ""
            Result.success()
        } finally {
            Backup.report(false)
            if (id.toString() != "" && tags.contains(BackupWorker::class.java.name)) Backup.armTrigger(applicationContext)
        }
    }

    private fun foregroundInfo(left: Int, name: String = ""): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sending to iCloud")
            .setContentText(if (name.isEmpty()) "$left to send" else "$name · $left left")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    private companion object {
        const val CHANNEL = "uploads"
        const val NOTIFICATION_ID = 11
    }
}
