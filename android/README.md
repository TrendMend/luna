# Android app

The Luna app for Android phones and tablets. It talks to Luna with voice and chat, shows widgets, rings for calls and reminders, and can listen for wake
words. Package `dev.luna.assistant`, `minSdk` 26.

It started from LiveKit's [agent-starter-android](https://github.com/livekit-examples/agent-starter-android) (Apache-2.0) and was changed as little as
possible: the same `Session`, `TokenSource`, microphone and audio handling, plus the pieces below.

**Status:** the unit tests pass (76 at last count) and the build and Android lint are clean. The maintainer reports (2026-11-13) that the app works in
production. No test records back that report.

## What is in it

### Connecting

- `Settings.kt` keeps the server address and the device access key (from the `luna-web add-device` link) in the app's private storage.
- `VoiceAssistantViewModel` gets its token with `TokenSource.fromEndpoint("<server>/api/token")` and `Authorization: Bearer <key>`. Luna's token
  service already speaks LiveKit's standard endpoint format.
- `ConnectScreen` takes the pasted setup link, then "Talk to Luna".
- The agent's `luna.vision` attribute decides whether the camera and screen-share buttons show. When the room closes (the agent hung up), the call screen ends.

### Look

- Material 3 theme in `ui/theme/` (light and dark, a 20sp body text scale, 28dp corners). It follows the phone's light or dark setting and does not use dynamic colour.
- `ui/LunaOrb.kt`: a moon that fills in as she listens and speaks, driven by the real audio level.
- `ui/Conversation.kt`: the orb and a plain-language status, the feed with widgets inline, a pill message field and big labelled buttons.
- `ui/Home.kt` (Home and Setup) and `ui/SettingsContent.kt` (Settings).
- Every screen is a stateless composable with previews in `app/src/screenshotTest/`. The rendered images are in `app/src/screenshotTestDebug/reference/`
  (regenerate with `updateDebugScreenshotTest`, check with `validateDebugScreenshotTest`). Look at them when changing the UI.

### Widgets

`Widgets.kt`, `Timeline.kt` and `ui/ChatLog.kt` show the agent's widgets inline in the conversation where they appeared. They arrive as A2UI v0.9.1 over the
`luna.ui` text stream, are drawn by the official `androidx.a2ui` Material 3 renderer, and taps go back on `luna.ui.action`. The app announces support with
the `luna.ui` attribute.

### Calls and reminders

With "Ring for calls and reminders" on, `ControlService.kt` and `TokenClient.kt` keep a quiet foreground connection to the device's control room
(`purpose: control`). A `luna.call` message (a due timer or reminder, or Home Assistant) becomes a full-screen notification that opens the app over the lock
screen and starts a conversation through the same screen as the Talk button, with `luna.trigger=call` and the words to open with in `luna.say`. A call never
interrupts a conversation already going. From Android 14 the app must also be allowed to open over the lock screen (the switch sends you to that setting).

### Wake words on the phone

Off by default ("Listen for wake words" in Settings).

- **Detection.** `wakeword/OpenWakeWord.kt` is openWakeWord's pipeline (melspectrogram, embedding, one small model per word) on ONNX Runtime, running the same
  `.onnx` models as the listener: `ron`, `rulon`, `alexa`, `hey_jarvis`, `hey_mycroft`, `hey_rhasspy` in `assets/wakeword/`. It is checked against the Python
  original frame by frame (`OpenWakeWordTest`; the reference scores come from `listener/tools/make_wakeword_golden.py`).
- **Models.** `ron` and `rulon` are in the repository. The others are openWakeWord's, licensed CC BY-NC-SA 4.0 (non-commercial), so they are not stored in git:
  run `python scripts/fetch_wakeword_models.py` once before building or running the unit tests (see [`../THIRD_PARTY.md`](../THIRD_PARTY.md)).
- **Settings.** `WakeWords.kt` holds the word list with the listener's thresholds (0.7 for `ron` and `rulon`, 0.5 for the rest) and the trigger rules (two
  chunks in a row, a 3 s cooldown, the highest score wins when two words overlap). One slider moves every word together; the button on a word's own row opens
  that word's slider and gives it a setting of its own ("Use the main setting" undoes it).
- **Service.** `WakeWordService.kt` is a foreground service of type `microphone`. It records 16 kHz audio, gives the microphone to the conversation when a word
  is heard, and listens again when it ends (`ConversationState`). The conversation starts with `luna.trigger=wake_word` and `luna.wake_word=<id>`, so the worker
  picks the greeting from `wake_words:` exactly as for the listener.
- **Opening from the background.** It tries, in order: Luna as the phone's selected assistant (`LunaInteractionService.show`, always allowed), the "display over
  other apps" permission (newer Android may still refuse a background start), then a full-screen notification (it opens over the lock screen; in use it is only a
  banner to tap).
- **Popup options** (Settings, "Popup"). `GreetingCue` plays one of six recorded clips (`res/raw/cue_*.wav`, Luna's Aoede voice, rendered once with Gemini Live)
  the moment a plain conversation opens, and tells the worker with `luna.greeted=1` so it stays quiet. "Show the end button" off hides the popup's end button
  (tapping outside the popup always closes it).

**Limits.** Android 14 and newer only lets a microphone service start while the app is on screen, and `BootReceiver` does not restart services (it only
re-applies an MDM's launcher-icon policy), so open the app once after a restart. Battery restrictions may stop the service.

### Managed configuration

An MDM can set the connection, names, calls, wake words and sensitivity, the language, the greeting and the popup end button, and can hide Settings or the app
icon: [`MDM.md`](MDM.md).

## Open it in Android Studio

1. Use Android Studio **Panda 4 (2025.3.4)** or newer, or Quail 1 (2026.1.1) or newer (AGP 9.2 needs it). Check Help, About.
2. File, Open, and pick **this `android/` folder** (not the repository root). Let Gradle sync; it downloads what it needs, including the Android 37.1 platform
   (accept the licences when asked). It writes `local.properties` and `.idea/` itself; both are git-ignored.
3. **Run on a phone:** turn on Developer options and USB debugging, plug it in, pick it in the device list, press Run.
   **Run on an emulator:** Device Manager, Create Device, a Pixel with a recent API image (with Google APIs). In the emulator's Extended controls, Microphone,
   turn on "Virtual microphone uses host audio input" so Luna can hear you. The emulator can reach a server on your network (for example
   `http://192.168.1.50:8080`) the same way a phone on Wi-Fi can.
4. **Watch what it does:** the Logcat panel, filtered to `package:mine`, shows the LiveKit connection and any errors.
5. **Previews:** open any file with `@Preview` functions and use the Split or Design view. To produce the same screens as PNG files without a device, run
   `./gradlew :app:updateDebugScreenshotTest`.

## Build and test

You need JDK 17 or newer (Android Studio bundles one) and the Android SDK (`ANDROID_HOME`). From this folder:

```sh
python ../scripts/fetch_wakeword_models.py                          # once
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Toolchain: AGP 9.2.1, Kotlin 2.3.0, `compileSdk` 37.1 (the A2UI libraries need it).

`WorkerMessagesTest` feeds the messages the worker really sends (`worker-widgets.jsonl`, kept in step by `worker/tests/test_android_fixture.py`) through the
official A2UI parser. It uses Gson as the tokenizer because `android.util.JsonReader` is only a stub on a JVM; drawing the widgets still needs a device.

## Security notes

Cleartext HTTP is allowed so the app can reach a home-LAN address, the access key is kept in the app's private storage (not the Android Keystore), and backups
are off. See [`../SECURITY.md`](../SECURITY.md).
