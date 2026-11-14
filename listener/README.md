# Listener

The endpoint that runs on a device people talk to: a small computer with a microphone and a speaker. It does three things:

1. **Listens for wake words locally.** No audio leaves the device until a wake word is heard.
2. **Starts a conversation.** It joins a LiveKit room, streams the microphone and plays Luna's voice.
3. **Waits for calls.** In a standing "control room" it receives `luna.call` messages (for example from Home Assistant, or a due reminder) and starts a
   conversation on its own.

The protocol is in [`../docs/protocol.md`](../docs/protocol.md).

## Try it

You need [uv](https://docs.astral.sh/uv/). Run these from this folder.

```sh
uv sync --extra wakeword --group dev
uv run luna-listener download-models      # openWakeWord's built-in models (alexa, hey_jarvis, hey_mycroft, ...)
uv run luna-listener devices              # list audio devices; set audio.input_device / output_device in config.yaml
uv run luna-listener test-wakeword        # say "hey jarvis": shows live scores, no network needed
```

To talk to Luna, run the worker's token service (`python -m luna.web` in `../worker`), make a key for this device (`luna-web add-device <name>`),
put it in `.env` as `LUNA_ACCESS_KEY=...`, and run:

```sh
uv run luna-listener run
```

## Configuration

[`config.yaml`](config.yaml) is declarative: the device name, the token service, audio devices and echo cancellation, the wake words (each with a
model and a threshold), the cooldown, and which trigger sources are on. The wake word **id** is sent to the worker, which uses it to choose what Luna
says (`wake_words:` in `../worker/config.yaml`).

| Setting | Environment override |
|---|---|
| `audio.input_device`, `audio.output_device` | `LUNA_INPUT_DEVICE`, `LUNA_OUTPUT_DEVICE` (these win over the file) |
| echo cancellation | `LUNA_ECHO_CANCELLATION` (`0` or `1`) |
| `audio.output_gain` | `LUNA_OUTPUT_GAIN` |
| wake-word model folder | `LUNA_MODELS_DIR` |
| config file, overrides file | `LUNA_LISTENER_CONFIG`, `LUNA_LISTENER_OVERRIDES` |

On NixOS the module sets these from its options (`inputDevice`, `outputDevice`, `outputGain`, `echoCancellation`).

## Wake words

openWakeWord's built-in words (`alexa`, `hey_jarvis`, `hey_mycroft`, `hey_rhasspy`, and the phrases `timer` and `weather`) come with the `openwakeword`
Python package. `timer` and `weather` listen for a phrase ("set a timer", "what's the weather") rather than a name, so they are off by default; every
word can be switched with `enabled:`.

Custom words are trained separately (livekit-wakeword generates synthetic speech) and produce an `.onnx` file; point `model:` at it.
`models/ron.onnx` and `models/rulon.onnx` are included. The built-in models are licensed CC BY-NC-SA 4.0 (non-commercial); see
[`../THIRD_PARTY.md`](../THIRD_PARTY.md).

## Microphone and speaker

By default the listener uses the system's default input and output. To pick others, name them in `config.yaml` (as listed by `luna-listener devices`)
or set `LUNA_INPUT_DEVICE` and `LUNA_OUTPUT_DEVICE`.

**PipeWire.** The listener reaches audio through ALSA, so a fixed microphone or speaker is a named ALSA device that points at a PipeWire node.
Put this in `/etc/alsa/conf.d/60-luna.conf` (or `~/.asoundrc`), using the node names from `wpctl status` or `pw-cli ls Node`:

```
pcm.luna_speaker { type pipewire; playback_node "my_speaker_sink"; hint { show on; description "Luna speaker" } }
pcm.luna_mic     { type pipewire; capture_node  "my_mic_source";   hint { show on; description "Luna microphone" } }
```

Then set `LUNA_OUTPUT_DEVICE=luna_speaker` and `LUNA_INPUT_DEVICE=luna_mic`. (`PIPEWIRE_ALSA` with a per-direction `target.object` looks like it should
do the same, but did not take effect in testing.) If the system already cancels echo (a PipeWire echo-cancel source and sink), set
`LUNA_ECHO_CANCELLATION=0` so the audio is not processed twice.

**Volume.** `audio.output_gain` is a software volume for Luna's voice only: 1.0 is as sent, 2.0 is twice the amplitude (about +6 dB). It does not
touch other programs on the same speaker. Sound louder than the speaker can play is clipped, so raise it in steps (1.5, 2.0, 3.0) and stop when
speech starts to crackle.

## Playback and the log line

Luna's voice is held in a small jitter buffer (150 ms) before it plays, and the buffer re-fills after any gap, so a late packet does not chop a word.
When a conversation ends the listener logs `playback stats`:

| Field | Meaning |
|---|---|
| `late_gaps`, `max_gap_ms` | Audio arrived more than 60 ms after the previous audio while speaking: the agent or the network was slow. |
| `underruns` | The buffer ran dry: nothing was left to play. |
| `device_underflows`, `max_cb_ms` | The sound system asked for audio before the listener had it: this process was starved of CPU. |
| `frames_in` | 10 ms frames received; about 100 per second of conversation (much more means audio is arriving twice). |

The agent publishes two audio tracks, `roomio_audio` (its speech) and `background_audio` (the thinking sound). The listener plays the speech only;
mixing both into one buffer chops the speech up.

## Extensions

Anything that can start a conversation is a *trigger source* (see `src/luna_listener/triggers.py`). The wake word and the Home Assistant call are
built in. Other packages can add sources through the `luna_listener.triggers` entry-point group and switch them on under `triggers:` in the config.

## Tests

```sh
uv run --group dev pytest -q        # add --extra wakeword to run the 3 tests that need openwakeword
```

They use fakes for the audio and LiveKit sides. More: [`../docs/development.md`](../docs/development.md).

## Status

- **Checked directly:** configuration; wake-word detection logic; the real openWakeWord model on silence and on a live microphone (`hey jarvis`
  detected); call-message parsing; token requests; the idle, conversation and cooldown loop with fakes; the playback buffer; and, against a real
  LiveKit server and worker, a call from the server API (the listener joins, the agent greets, the speech plays through the chosen speaker).
- **Reported:** the maintainer reports (2026-11-13) that the whole system works in production. No test logs back that report.
- **Not in this repository:** a Home Assistant-side component for calls. A `luna.call` message has to be sent with the LiveKit server API (see the protocol).
