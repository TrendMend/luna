# listener

The device-side endpoint. It listens for wake words **locally** (no audio leaves the device until one is
heard), then joins a LiveKit conversation room, streams the microphone and plays Luna's voice. It also waits in
a standing control room for calls from Home Assistant. The protocol is in [`../docs/protocol.md`](../docs/protocol.md).

## Try it (from this folder)

```
uv sync --extra wakeword --group dev
uv run luna-listener download-models      # openWakeWord's built-in models (alexa, hey_jarvis, hey_mycroft, ...)
uv run luna-listener devices              # list audio devices; set audio.input_device / output_device in config.yaml
uv run luna-listener test-wakeword        # say "hey jarvis": shows live scores, no network needed
```

Then, with the worker's token service running (`python -m luna.web` in `../worker`) and a key for this device
(`luna-web add-device nuc`), put the key in `.env` (`LUNA_ACCESS_KEY=...`) and run `uv run luna-listener run`.

## Config

`config.yaml` is declarative: the device name, the token service, audio devices and echo cancellation, the wake
words (each with a model and threshold), the cooldown, and which trigger sources are on. The wake word **id** is sent to
the worker, which uses it to choose what Luna says (`wake_words:` in `../worker/config.yaml`).

## Microphone and speaker

By default the listener uses the system's default input and output. To pick others, name them in `config.yaml`
(`audio.input_device` / `audio.output_device`, as listed by `luna-listener devices`) or, without touching the file, with
`LUNA_INPUT_DEVICE` / `LUNA_OUTPUT_DEVICE` (these win; the NixOS module sets them from `inputDevice` / `outputDevice`).

On a PipeWire system the listener reaches audio through ALSA, so a fixed microphone or speaker is a named ALSA device that
points at a PipeWire node. Put this in `/etc/alsa/conf.d/60-luna.conf` (or `~/.asoundrc`), using the node names from `wpctl status` / `pw-cli ls Node`:

```
pcm.luna_speaker { type pipewire; playback_node "my_speaker_sink"; hint { show on; description "Luna speaker" } }
pcm.luna_mic     { type pipewire; capture_node  "my_mic_source";   hint { show on; description "Luna microphone" } }
```
then `LUNA_OUTPUT_DEVICE=luna_speaker`, `LUNA_INPUT_DEVICE=luna_mic`. (`PIPEWIRE_ALSA` with a per-direction `target.object` looks
like it should do the same, but did not take effect in testing.) If the system already cancels echo (a PipeWire echo-cancel
source and sink), set `LUNA_ECHO_CANCELLATION=0` so the audio is not processed twice.

## Volume

`audio.output_gain` (or `LUNA_OUTPUT_GAIN`, NixOS `outputGain`) is a software volume for Luna's voice only: 1.0 is as sent, 2.0 is
twice the amplitude (about +6 dB). It does not touch other programs on the same speaker, which is the point when Luna shares a
speaker with something else. Sound louder than the speaker can play is clipped, so raise it in steps (1.5, 2.0, 3.0) and stop
when speech starts to crackle.

## Playback and its log line

Luna's voice is held in a small jitter buffer (150 ms) before it plays, and the buffer re-fills after any gap, so a late packet
does not chop a word. When a conversation ends the listener logs `playback stats`:

| Field | Meaning |
|---|---|
| `late_gaps`, `max_gap_ms` | audio arrived more than 60 ms after the previous audio while speaking: the agent or the network was slow |
| `underruns` | the buffer ran dry: nothing was left to play |
| `device_underflows`, `max_cb_ms` | the sound system asked for audio before the listener had it: this process was starved of CPU |
| `frames_in` | 10 ms frames received; about 100 per second of conversation (much more means audio is arriving twice) |

The agent publishes two audio tracks, `roomio_audio` (its speech) and `background_audio` (the thinking sound). The listener
plays the speech only; mixing both into one buffer chops the speech up.

## Extensions

Anything that can start a conversation is a *trigger source* (see `src/luna_listener/triggers.py`). The wake word and
the Home Assistant call are built in. Other packages can add sources through the `luna_listener.triggers`
entry-point group and are switched on under `triggers:` in the config.

## Custom wake words ("hey Luna", "hey Rulon")

openWakeWord ships only a few pre-trained words. Custom ones are trained separately (livekit-wakeword generates synthetic
speech) and produce an `.onnx` file; point `model:` at it. `models/ron.onnx` and `models/rulon.onnx` are included. All the
built-in names are on by default except `timer` and `weather`, which listen for a phrase ("set a timer", "what's the
weather") rather than a name, so they are off; switch any of them with `enabled:`.

## Status

Tested: configuration, wake-word detection logic, the real openWakeWord model on silence and on a live microphone
(`hey jarvis` detected), call-message parsing, token requests, the idle / conversation / cooldown loop with fakes, the
playback buffer, and, against a real LiveKit server and worker, a call from the server API: the listener joins, the agent
greets, and the speech plays through the chosen speaker.

**Production:** The maintainer reports (2026-11-13) that the whole system has been confirmed working in production use. No test logs or per-feature records back that report; it is the owner's statement.
The Home Assistant side of calls is not part of this repository (`homeassistant/` is a placeholder).
