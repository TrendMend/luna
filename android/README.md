# android

The Luna Android app. **Status: phase 1 (voice only), builds; not yet run on a device.**

It is LiveKit's [agent-starter-android](https://github.com/livekit-examples/agent-starter-android) (Apache-2.0), changed as little
as possible: the same `Session`, `TokenSource`, microphone and audio handling and UI, with these changes:

- `Settings.kt`: server address and device access key (from the `luna-web add-device` link), in private storage.
- `VoiceAssistantViewModel`: `TokenSource.fromEndpoint("<server>/api/token")` with `Authorization: Bearer <key>`. Luna's token
  service already speaks LiveKit's standard endpoint format.
- `ConnectScreen`: paste the setup link, then "Talk to Luna".
- Package `dev.luna.assistant`; cleartext HTTP allowed (home LAN); backups off.

Planned on top, one step at a time and each verified before the next: widgets (A2UI over `luna.ui`), then the calls and reminders
service. The earlier hand-written app is in git history (`main`, `android/`).

## Build

JDK 17, Android SDK (`ANDROID_HOME`); from this folder: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
(APK: `app/build/outputs/apk/debug/app-debug.apk`).
