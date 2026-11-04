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

## Extensions

Anything that can start a conversation is a *trigger source* (see `src/luna_listener/triggers.py`). The wake word and
the Home Assistant call are built in. Other packages can add sources through the `luna_listener.triggers`
entry-point group and are switched on under `triggers:` in the config.

## Custom wake words ("hey Luna", "hey Rulon")

openWakeWord ships only a few pre-trained words. Custom ones are trained separately (their training notebook
generates synthetic speech) and produce an `.onnx` file; point `model:` at it. Until then `hey_jarvis` is a stand-in.

## Status

Tested: configuration, wake-word detection logic, the real openWakeWord model on silence and on this machine's live
microphone (scores at 0.00 in silence), call-message parsing, token requests against a fake service, and the
idle / conversation / cooldown loop with fakes.

**Not tested yet:** a spoken wake word, joining a real LiveKit room, publishing the microphone, playing the agent's
voice, echo cancellation, and calls from Home Assistant. Those need the LiveKit server and the worker running.
