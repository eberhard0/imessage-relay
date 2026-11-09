# kotlinx.serialization keeps generated serializers reachable via reflection.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.eberhard.iphotos.** {
    *** Companion;
}
-keepclasseswithmembers class com.eberhard.iphotos.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
# Tink (security-crypto)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# WorkManager workers are created by class name.
-keep class com.eberhard.iphotos.work.** { *; }
