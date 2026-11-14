# Third-party notices

Luna's own code is licensed under the Apache License 2.0 (see `LICENSE`). Everything below is made by others and keeps its own license.
This is a list of **direct** dependencies and bundled or downloaded assets, with licenses taken from each package's published metadata
(PyPI, Maven POM, npm) when this file was written. It does not cover transitive dependencies. Check the lock files
(`worker/uv.lock`, `listener/uv.lock`, the Gradle dependency graph) if you need those.

## Wake word models

| File | Source | License |
|---|---|---|
| `melspectrogram.onnx`, `embedding_model.onnx`, `alexa.onnx`, `hey_jarvis.onnx`, `hey_mycroft.onnx`, `hey_rhasspy.onnx` | [openWakeWord](https://github.com/dscripka/openWakeWord) release v0.5.1 | **CC BY-NC-SA 4.0** (non-commercial, share-alike) |
| `ron.onnx`, `rulon.onnx` (in `listener/models/` and the Android assets) | Trained for this project with livekit-wakeword | This project's license (Apache-2.0) |

**The openWakeWord models are non-commercial and are not covered by this project's license.** They are not stored in this repository.
For the Android app, `python scripts/fetch_wakeword_models.py` downloads them (checked against pinned SHA-256 values) into
`android/app/src/main/assets/wakeword/`, which is git-ignored. The listener gets the same models from the `openwakeword` Python package.
If you want to use Luna commercially, replace them with models you are allowed to use (for example your own trained with
livekit-wakeword) and remove the built-in words from the configuration.

Note: earlier commits in this repository's history contained these files before they were moved to the download script.

## Python (worker and listener)

| Package | License |
|---|---|
| livekit-agents, livekit (rtc SDK), livekit-plugins-silero, -openai, -deepgram, -google, -anthropic, -elevenlabs, -cartesia, -assemblyai | Apache-2.0 |
| PyYAML | MIT |
| python-dotenv | BSD-3-Clause |
| segno | BSD |
| aiohttp | Apache-2.0 AND MIT |
| sounddevice | MIT |
| numpy | BSD-3-Clause (plus MIT, 0BSD, Zlib, CC0-1.0 parts) |
| openwakeword (code) | Apache-2.0 |
| onnxruntime | MIT |
| pytest (development only) | MIT |

Model weights that the worker downloads at build time (the Silero VAD and LiveKit's turn detector, see `worker/Dockerfile`) have
their own licenses, which have not been checked for this file.

## Android app

| Library | Version | License |
|---|---|---|
| io.livekit:livekit-android, livekit-android-compose-components | 2.28.0, 2.4.2 | Apache-2.0 |
| com.google.accompanist:accompanist-permissions | 0.37.3 | Apache-2.0 |
| com.google.code.gson:gson | 2.13.2 | Apache-2.0 |
| com.squareup.okhttp3:okhttp | 5.2.1 | Apache-2.0 |
| com.github.ajalt:timberkt | 1.5.1 | Apache-2.0 |
| io.coil-kt.coil3:coil-compose, coil-network-okhttp | 3.6.3 | Apache-2.0 |
| androidx.a2ui (A2UI model, engine, compose) | 1.0.0-alpha02 | Apache-2.0 |
| com.microsoft.onnxruntime:onnxruntime-android (and onnxruntime for unit tests) | 1.31.1 | MIT |
| AndroidX Compose, Material 3, core, lifecycle, activity, navigation, constraintlayout | see `android/gradle/libs.versions.toml` | Apache-2.0 (published by Google) |

## Web client

`web/index.html` loads `livekit-client` (Apache-2.0) from the jsDelivr CDN, or from `web/vendor/` if you run `web/vendor.sh`.

## Services and images

`docker-compose.yml` runs `livekit/livekit-server` (LiveKit's open-source server, Apache-2.0; license not re-checked here). The cloud
speech and language providers you configure (OpenAI, Deepgram, Google, Anthropic, ElevenLabs, Cartesia, AssemblyAI) are separate
services under their own terms.
