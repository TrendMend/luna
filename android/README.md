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
- **Look:** Material 3 theme (`ui/theme/`, light and dark, a 20sp body text scale, 28dp corners), Luna's orb (`ui/LunaOrb.kt`, a moon
  that fills in as she listens and speaks, driven by the real audio level), a conversation screen (`ui/Conversation.kt`: orb and
  plain-language status, feed with widgets inline, pill message field, big labelled buttons), Home and Setup (`ui/Home.kt`) and
  Settings (`ui/SettingsContent.kt`). Every screen is a stateless composable with previews in `app/src/screenshotTest/`; the
  rendered PNGs are in `app/src/screenshotTestDebug/reference/` (regenerate with `updateDebugScreenshotTest`, check with
  `validateDebugScreenshotTest`). Look at them when changing the UI.
- Package `dev.luna.assistant`; cleartext HTTP allowed (home LAN); backups off.
- Toolchain: AGP 9.2.1, Kotlin 2.3.0, `compileSdk 37.1` (the A2UI libraries need it), minSdk 26.

- `ControlService.kt`, `TokenClient.kt`: with "Ring for calls and reminders" on, a foreground service keeps a quiet connection to the
  device's control room (`purpose: control`). A `luna.call` message (a due timer or reminder, or Home Assistant) becomes a full-screen
  notification that opens the app over the lock screen and starts a conversation through the same screen as the Talk button, with
  `luna.trigger=call` and the words to open with in `luna.say`. A call never interrupts a conversation already going.
  From Android 14 the app must also be allowed to open over the lock screen (the switch sends you to that setting).
- `wakeword/`: **wake words on the phone** ("Listen for wake words" in Settings; off by default). `OpenWakeWord.kt` is openWakeWord's
  pipeline (melspectrogram, embedding, one small model per word) on ONNX Runtime, running the same `.onnx` models as the NUC's
  listener (`assets/wakeword/`: `ron`, `rulon`, `alexa`, `hey_jarvis`, `hey_mycroft`, `hey_rhasspy`). It is checked against the Python
  original frame by frame (`OpenWakeWordTest`; the reference scores are made by `listener/tools/make_wakeword_golden.py`).
  `WakeWords.kt` holds the word list with the NUC's thresholds (0.7 for `ron` and `rulon`, 0.5 for the rest) and the trigger rules
  (two chunks in a row, a 3 s cooldown, the highest score wins when two words overlap). One slider in Settings moves every word
  together; the button on a word's own row opens that word's slider right there, giving it a setting of its own ("Use the main setting" undoes it). `WakeWordService.kt` is a foreground service
  of type `microphone`: it records 16 kHz audio, gives the microphone to the conversation when a word is heard, and listens again
  when it ends (`ConversationState`). The conversation starts with `luna.trigger=wake_word` and `luna.wake_word=<id>`, so the worker
  picks the greeting from `wake_words:` exactly as for the NUC. To open from the background it tries, in order: Luna as the phone's
  selected assistant (`LunaInteractionService.show`, always allowed), the "display over other apps" permission (newer Android may
  still refuse a background start), then a full-screen notification (opens over the lock screen; in use it is only a banner to tap).
  Limits: Android 14+ only lets a microphone service start while the app is on screen, so open the app once after a restart; battery
  limits may stop it; the pre-trained openWakeWord models (all but `ron` and `rulon`) are licensed CC BY-NC-SA 4.0, for home use.
  Popup options (Settings, "Popup"): `GreetingCue` plays one of six recorded clips (`res/raw/cue_*.wav`, Luna's Aoede voice, rendered once with
  Gemini Live) the moment a plain conversation opens, and tells the worker with `luna.greeted=1` so it stays quiet; "Show the end button" off
  hides the popup's end button (tapping outside the popup always closes it).
  Not yet run on a device: the audio capture, the service and the background launch are untested.
  Limits: Android's battery restrictions may stop the service; it does not restart at boot (open the app once after a reboot). The earlier hand-written app is in git history (`main`, `android/`).

## Open it in Android Studio

1. Android Studio **Panda 4 (2025.3.4)** or newer, or Quail 1 (2026.1.1) or newer (AGP 9.2 needs it). Check Help, About.
2. File, Open, and pick **this `android/` folder** (not the repository root). Let Gradle sync; it downloads what it needs, including
   the Android 37.1 platform (accept the licences when asked). It writes `local.properties` and `.idea/` itself; both are git-ignored.
3. **Run on a phone:** turn on Developer options and USB debugging, plug it in, pick it in the device list, press Run.
   **Run on an emulator:** Device Manager, Create Device, a Pixel with a recent API image (with Google APIs), then in the emulator's
   Extended controls, Microphone, turn on "Virtual microphone uses host audio input" so Luna can hear you. The emulator can reach
   the NUC on the home network (`http://192.168.1.50:8080`), the same as a phone on Wi-Fi.
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
