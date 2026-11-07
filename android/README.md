# android

The Luna Android app. **Status: voice, chat, widgets and calls/reminders build and pass their unit tests; not yet run on a device.**

It is LiveKit's [agent-starter-android](https://github.com/livekit-examples/agent-starter-android) (Apache-2.0), changed as little
as possible: the same `Session`, `TokenSource`, microphone and audio handling and UI, with these changes:

- `Settings.kt`: server address and device access key (from the `luna-web add-device` link), in private storage.
- `VoiceAssistantViewModel`: `TokenSource.fromEndpoint("<server>/api/token")` with `Authorization: Bearer <key>`. Luna's token
  service already speaks LiveKit's standard endpoint format.
- `ConnectScreen`: paste the setup link, then "Talk to Luna".
- `Widgets.kt`, `Timeline.kt`, `ui/ChatLog.kt`: the agent's widgets (A2UI v0.9.1 over the `luna.ui` text stream, drawn by the official
  `androidx.a2ui` Material 3 renderer, taps back on `luna.ui.action`), shown inline in the conversation where they appeared. The app announces support with the `luna.ui` attribute.
- The agent's `luna.vision` attribute decides whether the camera and screen-share buttons show; the room closing (the agent hung
  up) ends the call screen.
- Package `dev.luna.assistant`; cleartext HTTP allowed (home LAN); backups off.
- Toolchain: AGP 9.2.1, Kotlin 2.3.0, `compileSdk 37.1` (the A2UI libraries need it), minSdk 26.

- `ControlService.kt`, `TokenClient.kt`: with "Ring for calls and reminders" on, a foreground service keeps a quiet connection to the
  device's control room (`purpose: control`). A `luna.call` message (a due timer or reminder, or Home Assistant) becomes a full-screen
  notification that opens the app over the lock screen and starts a conversation through the same screen as the Talk button, with
  `luna.trigger=call` and the words to open with in `luna.say`. A call never interrupts a conversation already going.
  From Android 14 the app must also be allowed to open over the lock screen (the switch sends you to that setting).
  Limits: Android's battery restrictions may stop the service; it does not restart at boot (open the app once after a reboot). The earlier hand-written app is in git history (`main`, `android/`).

## Open it in Android Studio

1. Android Studio **Panda 4 (2025.3.4)** or newer, or Quail 1 (2026.1.1) or newer (AGP 9.2 needs it). Check Help, About.
2. File, Open, and pick **this `android/` folder** (not the repository root). Let Gradle sync; it downloads what it needs, including
   the Android 37.1 platform (accept the licences when asked). It writes `local.properties` and `.idea/` itself; both are git-ignored.
3. **Run on a phone:** turn on Developer options and USB debugging, plug it in, pick it in the device list, press Run.
   **Run on an emulator:** Device Manager, Create Device, a Pixel with a recent API image (with Google APIs), then in the emulator's
   Extended controls, Microphone, turn on "Virtual microphone uses host audio input" so Luna can hear you. The emulator can reach
   the NUC on the home network (`http://10.0.0.90:8080`), the same as a phone on Wi-Fi.
4. **Watch what the app does:** the Logcat panel, filtered to `package:mine`, shows the LiveKit connection and any errors. On the NUC,
   `sudo journalctl -u luna-web -u luna-agent -f` shows the other side.
5. **Previews:** open any file that has `@Preview` functions and use the Split or Design view. To produce the same screens as PNG
   files without a device, run `./gradlew :app:updateDebugScreenshotTest` (images land in `app/src/screenshotTestDebug/reference/`).

## Build

JDK 17, Android SDK (`ANDROID_HOME`); from this folder: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
(APK: `app/build/outputs/apk/debug/app-debug.apk`). Gradle needs JDK 17 or newer, which Android Studio bundles.

## Tests

`./gradlew :app:testDebugUnitTest`. `WorkerMessagesTest` feeds the messages the worker really sends (`worker-widgets.jsonl`, kept
in step by `worker/tests/test_android_fixture.py`) through the official A2UI parser. It uses Gson as the tokenizer because
`android.util.JsonReader` is only a stub on a JVM; drawing the widgets still needs a device.
