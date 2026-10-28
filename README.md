# iMessage Relay

iMessages from the MacBook Neo on the Galaxy Z Fold 8, over Tailscale.

```
Messages.app (Mac) → chat.db → imsg-bridge (Mac, 100.90.106.50:8765) → Tailscale → Relay (Android)
```

- `bridge/`: `imsg-bridge`, a single Go binary for Apple silicon. Reads `~/Library/Messages/chat.db`
  read-only, streams new messages, sends with AppleScript (text passed as an argument, never spliced
  into the script). Listens only on the tailnet address; every request needs the bearer token.
- `android/`: the Relay app (Kotlin, Compose). A foreground service keeps the event stream open; one
  notification per conversation with inline reply; conversation list and thread, two panes when unfolded.

## Bridge API

All endpoints need `Authorization: Bearer <token>`.

| Endpoint | Purpose |
|---|---|
| `GET /v1/health` | chat.db readable, Automation status, version |
| `GET /v1/chats?limit=` | conversations with last message and unread count |
| `GET /v1/chats/{guid}/messages?before=&limit=` | history, oldest first, paged by message ROWID |
| `GET /v1/events?since=` | Server-Sent Events, both directions; `since` / `Last-Event-ID` catches up (24 h) |
| `POST /v1/send` | `{"chat_guid": "…", "text": "…"}` or `{"handle": "…", "text": "…"}` |
| `GET /v1/attachments/{id}` | attachment bytes; HEIC converted to JPEG |

## Mac setup

1. Put the binary in place (fixed path, so macOS permissions stick):
   ```
   mkdir -p ~/Applications/imsg-bridge
   mv imsg-bridge ~/Applications/imsg-bridge/ && chmod +x ~/Applications/imsg-bridge/imsg-bridge
   ~/Applications/imsg-bridge/imsg-bridge init          # prints the token for the app
   ```
2. On the Mac's screen (Screen Sharing works, SSH doesn't): System Settings › Privacy & Security ›
   Full Disk Access › + › Cmd-Shift-G › `~/Applications/imsg-bridge/imsg-bridge`.
3. `~/Applications/imsg-bridge/imsg-bridge install-agent` starts it now and at every login.
   The first time it talks to Messages, macOS asks on the Mac's screen to allow Automation. Click OK.
4. In Relay on the Fold: address `http://100.90.106.50:8765`, the token, then **Test and save**.

Replacing the binary with a new build changes its signature, so macOS asks for Full Disk Access
and Automation again.

**Sleep:** the bridge runs `caffeinate -s`, which keeps the Mac awake **on the charger** even with the
lid closed. On battery with the lid closed the Mac sleeps and messages stop until it wakes.

Logs: `~/Library/Logs/imsg-bridge.log` (counts and errors only, never message text).
Stop: `imsg-bridge uninstall-agent`.
