# android

The Luna Android app (Kotlin, Jetpack Compose). **Status: builds and passes its unit tests; never run on a device or emulator.**

## What it does

- **Conversation:** the big button gets a token from the worker (`POST /api/token`, the device's access key), joins the LiveKit
  room, publishes the microphone, plays the agent, and shows the transcript.
- **Widgets:** announces the `luna.ui` attribute, so the worker offers it widget tools. A2UI messages arrive on the `luna.ui`
  text stream and are drawn by the official `androidx.a2ui` Compose renderer (Material 3 basic catalog); taps and errors go back
  on `luna.ui.action`. Protocol: [`../docs/protocol.md`](../docs/protocol.md).
- **Calls and reminders:** with "Ring for calls and reminders" on, a foreground service keeps the control-room connection. A
  `luna.call` message (Home Assistant, or a due timer/reminder) becomes a full-screen notification that opens the app over the
  lock screen and starts a conversation with the words to open with.
- **Setup:** Settings takes the server address and access key; pasting the link from `luna-web add-device` fills both.

## Build

JDK 17, the Android SDK (`ANDROID_HOME`), then from this folder:

```
./gradlew :app:testDebugUnitTest     # protocol logic (a JVM, no device needed)
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

Versions that matter: AGP 9.2.1 (needs Gradle 9.4.1, which the wrapper downloads), `compileSdk 37.1` (the A2UI libraries need it;
Gradle installs the platform once the SDK licence is accepted), minSdk 26. LiveKit's Android SDK takes one dependency from JitPack;
`settings.gradle.kts` allows only that group from there.

## What is and is not verified

Verified: it compiles; 9 unit tests cover the wire format (`Protocol.kt`: calls, token body, action and error messages, links, JSON
Lines) and that our catalog id equals the one the renderer registers (a mismatch there would reject every widget).

**Not verified, because there is no emulator image or device here:** that the app starts; the microphone and audio; that a
LiveKit room connects; that the worker's widget JSON parses with the Android A2UI parser and draws (the web renderer draws the
same messages, the Android one has not seen them); the control-room service, the full-screen call notification, and battery
restrictions killing the service (Android may stop it; there is no boot receiver yet). Treat the first run on a phone as the real test.

## Choices worth knowing

- `usesCleartextTraffic` is on so a LAN `http://` server works. Use `https://` anywhere else.
- Backups are off (`allowBackup=false`) so the device key stays out of cloud backups. The key is in private app storage, not the Keystore.
- LiveKit's library asks for camera and screen-share permissions; the manifest removes them.
- On-device wake words (what the old HAWakeWord app did) are not here. `livekit-wakeword` is the likely route.
- An earlier standalone project (HAWakeWord) was removed from this folder; it is in git history.
