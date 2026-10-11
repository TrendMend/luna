# HA Wake Word (Android)

Listens on-device for your custom openWakeWord `.onnx` model(s) and fires a
`hawakeword_detected` event in Home Assistant, so you can trigger automations without
the Assist voice pipeline.

```
mic -> openWakeWord (ONNX, on phone) -> mobile_app webhook (fire_event) -> HA event trigger
```

It follows the [native app integration](https://developers.home-assistant.io/docs/api/native-app-integration/)
flow: OAuth sign-in once, register the device with `mobile_app`, then only the
webhook id is used. The app appears as a device in HA and can be revoked there.

## Setup

1. **Serve `docs/index.html` from HA itself (no public URL needed).** Copy it to
   `/config/www/hawakeword.html` (File editor / Samba / SSH). HA then serves it at
   `http://<ha-host>:8123/local/hawakeword.html`, which the app uses as its OAuth
   `client_id` by default. HA fetches that page to confirm the `hawakeword://auth`
   redirect is allowed, and accepts private/LAN addresses as client IDs.
   - The URL must be reachable *from the HA server*. If `homeassistant.local` doesn't
     resolve there, use the IP address or `http://localhost:8123/local/hawakeword.html`
     in the app's client_id field.
   - If you change the redirect scheme, change the page, `HaAuth.REDIRECT_URI` and the manifest.
2. Open this folder in **Android Studio** (it generates the Gradle wrapper and syncs).
3. Models: pre-trained words (`hey_jarvis`, `alexa`, `hey_mycroft`, `hey_rhasspy`), two custom
   [livekit-wakeword](https://github.com/livekit/livekit-wakeword) models (`ron`, `rulon`;
   on by default, detect threshold 0.5; raise it toward 0.85 if you get false alarms) and the two required helper models (`melspectrogram.onnx`,
   `embedding_model.onnx`) are already in `app/src/main/assets/` (~5.6 MB, from the
   [openWakeWord v0.5.1 release](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1)).
   Add your own by dropping `.onnx` files there, or import them from the app with
   **Import model (.onnx)** (see `docs/DEVELOPERS.md`). A `_v0.1` suffix is stripped, so
   `hey_jarvis_v0.1.onnx` reports `wake_word: hey_jarvis`. Every model in assets is
   listened for at once; switch off any you don't want in the app (restart listening to apply).
4. Build and install on your phone.
5. Open the app. First run guides you through permissions and Home Assistant sign-in
   (leave the OAuth client_id blank to use the page you copied in step 1).
6. Create the automation from `homeassistant/automation_example.yaml`, then press
   **Start listening** on the home screen (**Send test event** is in Settings).

Requirement: the `mobile_app` integration must be loaded (included in `default_config`).

## Using the app

**First run** walks you through setup, one step per screen, and always shows real state so it
can be left and resumed:

1. **Microphone** (and notifications on Android 13+). Audio is processed on the phone only.
2. **Keep it running**: allow unrestricted battery use. On Samsung/Xiaomi/OnePlus/etc. also set
   Battery to *Unrestricted* and enable auto-start in the app's system settings, or the vendor
   will still kill the listener. See [dontkillmyapp.com](https://dontkillmyapp.com).
3. **Connect Home Assistant**: enter the URL and sign in in the browser.
4. **Wake words**: the bundled words are listed; import your own if you want.

**Home** is deliberately minimal: whether it's listening, a Start/Stop button, the last
detection, a single "needs attention" card if something is off, and the wake words. Each word
has a switch and a **gear** that opens that word's own settings (detect and send threshold,
and Remove for words you added). **Add wake word** imports an `.onnx` model.

**Settings** (top right) holds the app-wide options: Home Assistant connection and device
name, the default detect/send thresholds and cooldown, start on boot, and the
permission/background checklist. The app version is shown there and on the home screen.

## Tuning

There are two thresholds. The app-wide defaults are in Settings; each word can override
them from its gear on the home screen:

- **Detect**: the score at which a word counts as heard.
- **Send**: the minimum score for the event to actually be sent to HA. It can't be lower
  than the detect threshold, and `0` means "same as detect". Detections that land between
  the two are shown in the app as "below send threshold, not sent" but never reach HA.

A typical use is a low detect threshold so you can watch near-misses on the home screen, and
a higher send threshold so only confident hits trigger automations. The "Last:" line shows
the most recent word, its score and whether it was sent. If the listener is running, changing
a word's settings restarts it automatically.

## More automation examples

- `homeassistant/automation_example.yaml`: minimal, shows a notification for any word.
- `homeassistant/blueprints/automation/hawakeword/calm_callouts.yaml`: a **blueprint** that
  speaks a reply at full volume on a phone you pick when chosen words are called. The default
  is soothing an elderly relative who calls "Ron" or "Rulon": overnight it says they're
  asleep and how long until morning, and on chosen weekdays in an "away" window it says
  they're at work and when they'll be back. Words, windows, messages, volume and the phone
  are all inputs, so you can create one automation per phone or trial it on another device.

Install the blueprint by copying the file to `/config/blueprints/automation/hawakeword/` on
your Home Assistant (File editor, Samba or SSH), then **Settings > Automations > Create
automation > Use blueprint**. It binds to a phone by its mobile_app device: the app learns its
Home Assistant device id (`get_config`) and includes it in every event as `device_id`, which is
exactly what the blueprint's device picker returns, so no names have to match.

This app and the Companion app are separate devices on the same phone. The blueprint has two
pickers: the **wake word app device** (e.g. `moto play_wake_word`, which hears) and the
**Companion app device** (`moto play`, which speaks via `notify.mobile_app_<name>`). Leave the
second empty to use the first one's name without `_wake_word`. Events from older builds without
an id fall back to matching the device name.

## Event payload

Event type `hawakeword_detected`, data:

```json
{"wake_word": "hey_lights", "score": 0.87, "device": "Pixel 8", "timestamp": 1760000000000}
```

Use `{{ trigger.event.data.wake_word }}` in templates. You can watch events live in
Developer Tools > Events (listen to `hawakeword_detected`).

## Managed (password-locked) build

`./gradlew assembleManagedRelease -PhaUrl=http://homeassistant.local:8123` builds a variant for
institutions and kiosks: the installer sets an admin password during setup, and settings,
wake words and stopping the listener are then locked behind it. See [docs/MANAGED.md](docs/MANAGED.md).

## Notes

- Delivery order: Cloudhook URL, remote UI URL, then local URL. If the device is deleted
  in HA (HTTP 410) the app clears its registration and asks you to sign in again.
- Webhook payloads are encrypted (libsodium secretbox) using the secret HA returns at
  registration. Once a registration has a secret, HA rejects unencrypted payloads, so the
  webhook id alone can't be used to fire events. If HA returns no secret (no PyNaCl), the app
  falls back to plain payloads. Sign in again after upgrading from a build without encryption.
- Registration data is kept in `EncryptedSharedPreferences`.
- **Device name:** set it in the app (blank = `<phone model>_wake_word`, so it doesn't collide
  with the Companion app's device). It's used when registering, and
  **Update name in Home Assistant** pushes a change via `update_registration`.
- **Keeping HA in sync:** on app open and when listening starts, the app compares app version,
  Android version, model and device name with what HA last received and sends
  `update_registration` only if something changed. HA's device page then shows the current app
  and OS version after updates. Re-registering with the same device ID replaces the old entry
  and keeps the same HA device and entities, so there's no need to delete it first.
- Runs as a microphone foreground service; disable battery optimization for the app.
- Start-on-boot may be blocked on Android 14+; open the app once after reboot if so.
- Not compiled in this environment. The detection pipeline is in `WakeWordEngine.kt`
  (ONNX Runtime directly); see `docs/DEVELOPERS.md` for the model contract and preloading.
