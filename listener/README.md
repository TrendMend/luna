# listener

The device-side endpoint: listens for wake words locally, then joins a LiveKit conversation room and streams
the microphone. Also waits in its control room for calls from Home Assistant (see `../docs/protocol.md`).

Status: not built yet. Planned: openWakeWord (ONNX) detection, trigger plugins (wake word, LiveKit call,
more later), echo cancellation with LiveKit's audio processing module.
