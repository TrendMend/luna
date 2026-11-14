# Luna

<p align="center"><img src="docs/images/icon.png" width="128" alt="The Luna app icon"></p>

Luna is a voice assistant for an older person living at home. You talk to it (on a phone, a tablet, a browser or a small always-on
device) and it answers questions, controls [Home Assistant](https://www.home-assistant.io) (lights, TV, fan; it can read temperatures and door status),
sets timers and reminders, shows widgets such as a weather card, and passes requests only a person can do to a caregiver. What it can do is limited to an allowlist inthe config, and there is a dry-run mode, but no per-action approval step (see [`SECURITY.md`](SECURITY.md)). The conversation runs over
[LiveKit](https://livekit.io); the language model and speech providers are configurable in `worker/config.yaml` (separate speech-to-text, model and
text-to-speech plugins, or a realtime model such as Gemini Live).

<p>
  <img src="android/app/src/screenshotTestDebug/reference/dev/luna/assistant/ConversationPreviewsKt/ConversationSpeakingLight_2edb4dbe_0.png" width="220" alt="Conversation screen with a confirmation widget">
  <img src="android/app/src/screenshotTestDebug/reference/dev/luna/assistant/HomePreviewsKt/HomeLight_2edb4dbe_0.png" width="220" alt="Home screen">
  <img src="android/app/src/screenshotTestDebug/reference/dev/luna/assistant/HomePreviewsKt/SettingsLight_2edb4dbe_0.png" width="220" alt="Settings screen">
</p>

These are the Android app's screenshot-test renders (`android/app/src/screenshotTestDebug/reference/`), drawn from sample data, not
captures from a device.

## Architecture

```
  phone / tablet / browser            small device (PC, NUC, Pi)
  Android app, web UI                 listener: wake words, calls
          |                                   |
          |  1. POST /api/token (access key)  |
          v                                   v
   +--------------------+   token    +-----------------+
   | worker: token      | ---------> |  LiveKit server |  audio, text, widgets
   | service (luna.web) |            |   (rooms)       |  <------------------+
   +--------------------+            +-----------------+                     |
                                             ^                               |
                                             | agent joins the room          |
                                      +------------------+  tools   +----------------+
                                      | worker: agent    | -------> | Home Assistant |
                                      | (model, tools)   |          | weather, search|
                                      +------------------+          +----------------+
```

| Folder | What it is |
|---|---|
| [`worker/`](worker/) | the LiveKit agent (speech, model, tools, A2UI widgets) and the token and reminder service |
| [`listener/`](listener/) | device endpoint: local wake words (openWakeWord), calls from Home Assistant, LiveKit audio |
| [`web/`](web/) | browser UI that can be installed as a home-screen app |
| [`android/`](android/) | Android app: conversation, widgets (official A2UI renderer), calls and reminders, on-device wake words, MDM configuration ([`android/MDM.md`](android/MDM.md)) |
| [`homeassistant/`](homeassistant/) | Home Assistant side of "make Luna call a device": placeholder, not started (the message it would send is in the protocol) |
| [`deploy/`](deploy/), [`docker-compose.yml`](docker-compose.yml), [`flake.nix`](flake.nix), [`nix/`](nix/) | Kubernetes, Compose, Nix package and NixOS modules |
| [`docs/`](docs/) | [the protocol](docs/protocol.md) between the parts, [deployment](docs/deployment.md), [releasing](docs/releasing.md) |

## Quick start

You need [uv](https://docs.astral.sh/uv/) and API keys for the providers named in `worker/config.yaml` (copy `.env.example` to `.env`).
Everything starts in dry-run mode (`LUNA_DRY_RUN=1`): Home Assistant is read, nothing is switched, nobody is notified.

```sh
cd worker
uv sync --extra openai --extra deepgram --extra cartesia   # the extras config.yaml uses (google for Gemini)
uv run python -m livekit.agents download-files
uv run python -m luna.agent console             # talk through this computer's microphone and speakers (no LiveKit server needed)
```

Run the tests (none of them needs a network, a device or a key):

```sh
cd worker   && uv run --extra google --group dev pytest -q
cd listener && uv run --group dev pytest -q
node --test web/test
python scripts/fetch_wakeword_models.py          # once, for the Android tests (downloads non-commercial models, see below)
cd android  && ./gradlew :app:testDebugUnitTest :app:assembleDebug     # JDK 17 and the Android SDK
```

Running the full system (LiveKit server, token service, devices): [`docs/deployment.md`](docs/deployment.md).

## Verified and not yet verified

Last checked by running the commands above on 2026-11-13: worker 142 tests, listener 21 (24 with `--extra wakeword`, which three tests need), web 6 and Android 76 unit tests pass; a fresh clone gave the same results.
Those tests use fakes for Home Assistant, weather, TV, LiveKit delivery and the model providers.

What the project's own notes (the component READMEs and [`CHANGELOG.md`](CHANGELOG.md)) say has been run for real:
- the worker in console mode with real provider keys, including a spoken conversation with a door-status tool call;
- the listener's wake-word detection on a live microphone (`hey jarvis`), and a call from the LiveKit server API in which the listener
  joined, the agent greeted and the speech played;
- a headless-browser check of the web renderer (13 checks).

**Not yet verified**, from the same notes: wake words on the phone on a real device (audio capture, background launch, battery use);
managed configurations against a real MDM; the custom `ron` / `rulon` models by voice; a full back-and-forth through the
listener's microphone; real Home Assistant actions (everything so far was dry-run); timers and reminders delivered through a real
LiveKit server; the Docker image, Kubernetes manifests and NixOS services running; echo cancellation. The notes disagree about whether
the Android app as a whole has been run on a device; treat it as unverified until that is written down. See [`TODO.md`](TODO.md).

## License

The project's code is Apache-2.0 ([`LICENSE`](LICENSE)). Third-party code and models keep their own licenses
([`THIRD_PARTY.md`](THIRD_PARTY.md)). In particular openWakeWord's pre-trained models are **non-commercial** (CC BY-NC-SA 4.0) and are
not stored here: `scripts/fetch_wakeword_models.py` downloads them for the Android app.
