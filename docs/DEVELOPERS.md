# Developer guide

## Layout

| File | Role |
|---|---|
| `WakeWordEngine.kt` | Mic -> mel -> embedding -> classifiers (ONNX Runtime). One shared feature pipeline, many classifiers |
| `WakeWordService.kt` | Foreground service; builds the engine from enabled models + prefs; forwards detections |
| `ModelCatalog.kt` | Finds models (assets + imported), validates and imports them |
| `HaAuth.kt` / `HaClient.kt` / `Crypto.kt` | OAuth sign-in, `mobile_app` registration, encrypted `fire_event` |
| `Prefs.kt` | Settings and registration, in `EncryptedSharedPreferences` |
| `MainActivity.kt` | Home: status, start/stop, warning card, word list (switch + gear sheet per word), add word. Routes to onboarding on first run |
| `OnboardingActivity.kt` | First-run wizard (welcome, permissions, background, HA sign-in, models) |
| `SettingsActivity.kt` | App-wide settings: HA connection, default thresholds, cooldown, boot, permission checklist |
| `AuthRedirectActivity.kt` | Handles `hawakeword://auth`, finishes sign-in, returns to onboarding/settings |
| `Setup.kt` | Permission/battery/version helpers shared by the screens |

UI is Material 3 with ViewBinding; layouts live in `res/layout`.

## Model contract

Wake word models are **openWakeWord classifiers** exported to ONNX:

- Input: float32 `[1, N, 96]`, the last `N` embedding frames. `N` is read from the model (usually 16).
- Output: float32 `[1, 1]`, a score in 0..1.
- Always needs the two shared helper models next to them:
  - `melspectrogram.onnx`
  - `embedding_model.onnx`

Anything else (a full Whisper model, a classifier with a different input width) is rejected on
import with "Not an openWakeWord classifier".

Train your own with the
[openWakeWord training notebook](https://github.com/dscripka/openWakeWord#training-new-models)
and export the `.onnx` it produces.

## Naming

The file name becomes the `wake_word` value sent to HA, with a trailing version stripped:

| File | `wake_word` |
|---|---|
| `hey_jarvis_v0.1.onnx` | `hey_jarvis` |
| `computer.onnx` | `computer` |

Use `[A-Za-z0-9._-]` only. Imported names are sanitised to that set.

## Preloading models (build time)

Put files in `app/src/main/assets/` and rebuild. They ship inside the APK and every
install has them. `.onnx` is already marked `noCompress` in `app/build.gradle.kts`.

```
app/src/main/assets/
  melspectrogram.onnx
  embedding_model.onnx
  hey_jarvis_v0.1.onnx
  my_word.onnx
```

The four pre-trained openWakeWord words and the two helper models are already committed
in `assets/`. To restore or re-fetch them, download these from the
[openWakeWord v0.5.1 release](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1)
into `app/src/main/assets/`: `melspectrogram.onnx`, `embedding_model.onnx`,
`hey_jarvis_v0.1.onnx`, `alexa_v0.1.onnx`, `hey_mycroft_v0.1.onnx`,
`hey_rhasspy_v0.1.onnx`.

`timer_v0.1.onnx` and `weather_v0.1.onnx` exist in the same release but are not bundled: on a
phone `weather` never responded and `timer` stayed pinned near 1.

## Adding models at runtime

- **In the app:** *Add wake word (.onnx)* on the home screen opens the system file picker. The file is validated
  (loads in ONNX Runtime, input is `[1, N, 96]`) and copied to `filesDir/models/`.
  Imported files take priority over a bundled file with the same name and can be removed
  from the word's gear sheet. The helper models can be imported the same way.
- **From a computer (debug builds):**

  ```bash
  adb push my_word.onnx /sdcard/Download/
  # then use "Import model" in the app and pick it from Downloads
  ```

  or copy straight into app storage without the picker:

  ```bash
  adb shell run-as com.example.hawakeword mkdir -p files/models
  adb push my_word.onnx /data/local/tmp/
  adb shell run-as com.example.hawakeword cp /data/local/tmp/my_word.onnx files/models/
  ```

  This skips validation, so a bad file makes the listener stop with an error notification.

## Thresholds

Two per model, each a global default with an optional per-word override:

- **Detect** (`Prefs.thresholdFor`): scores at or above it are reported by the engine.
- **Send** (`Prefs.sendThresholdFor`, never below detect): reported detections at or above it
  have `Detection.send = true` and are forwarded to HA; the rest only update the "Last:" line.

The cooldown is tracked separately for sent and unsent detections of a word, so a weak
unsent detection can't suppress a strong one that follows it.
openWakeWord's own default is 0.5. The "Last:" line in the app shows the score of recent
detections to help tune.

## Audio notes

- 16 kHz, mono, 16-bit, in 1280-sample (80 ms) chunks.
- The mel model receives raw int16 values as floats (**not** divided by 32768). This matches
  the Python library; normalising to +/-1 makes scores far too low.
- 30 chunks of random noise prime the feature buffer at start, and the first 5 real chunks
  are ignored, as openWakeWord does.

## Testing a model off-device

Check a model's shape before shipping it:

```python
import onnxruntime as ort
s = ort.InferenceSession("my_word.onnx")
print([(i.name, i.shape) for i in s.get_inputs()])   # expect [1, 16, 96] or similar
```

The Python openWakeWord package can score the same model on a recorded clip, which is
the easiest way to compare against what the app reports.

## 16 KB page size

Android 15+ devices (and Google Play) require native libraries to be 16 KB aligned
([guide](https://developer.android.com/guide/practices/page-sizes)).

What this project does:

- **AGP 8.5.2** (needs 8.5.1+), so uncompressed `.so` files are zip-aligned to 16 KB.
- **ONNX Runtime 1.24.1** is the only native dependency. 1.17.x was not 16 KB aligned.
- **No other native code.** Webhook encryption uses pure-Java BouncyCastle instead of
  libsodium/JNA, which would add `.so` files that have to be aligned too.

Quick check of the native libraries from the last build (no SDK tools needed):

```powershell
python tools\check_elf_alignment.py        # every .so should print OK
```

The checks below are Google's official ones, run on the built APK (build-tools and NDK paths from your SDK):

```powershell
# 1. zip alignment
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\zipalign.exe" -c -P 16 -v 4 app-debug.apk

# 2. ELF alignment: every LOAD line for arm64-v8a and x86_64 should say align 2**14
mkdir apkout; tar -xf app-debug.apk -C apkout
& "$env:LOCALAPPDATA\Android\Sdk\ndk\<version>\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-objdump.exe" -p apkout\lib\arm64-v8a\*.so | Select-String LOAD
```

Android Studio's **Build > Analyze APK** also has an Alignment column, and
`adb shell getconf PAGE_SIZE` returns `16384` on a 16 KB emulator image or a Pixel with the
"Boot with 16KB page size" developer option.

If a new dependency adds a `.so` that fails, update it to a release built with NDK r28+
(16 KB by default) or find a pure-Java alternative.

## Versioning

`version.properties` holds `versionName` (semver, bump by hand per release and tag it
`git tag v0.3.0`). `versionCode` is the git commit count, so it increases with every commit.
The app shows `v<versionName> (<versionCode>)` (managed builds add `-managed`), read from the
installed package, so what's displayed is exactly what the APK was built with.

## Managed variant

Product flavors `standard` / `managed` (dimension `mode`) set `BuildConfig.LOCKED`,
`AUTH_SCHEME` (also the manifest redirect scheme) and `DEFAULT_HA_URL` (`-PhaUrl=`).
`AdminLock` holds the PBKDF2 password check, throttling and the timed unlock session;
`MainActivity`, `SettingsActivity` and the service notification consult it. Add new
protected actions with `AdminLock.require(activity) { ... }`. Details: [MANAGED.md](MANAGED.md).

## Limits and known gaps

- The cooldown is one value for all words.
- Changing models or thresholds takes effect when listening is restarted.
- Not compiled or run in the environment this was written in; first build may need small fixes.
