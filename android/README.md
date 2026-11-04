# android

The Luna Android app. **Status: not started.** This folder is a placeholder for the plan below.

## Plan

Base it on LiveKit's open-source Android agent starter (`livekit-examples/agent-starter-android`) and adapt it to Luna:

- **Token:** get it from the worker's token service (`POST /api/token`, standard LiveKit token-endpoint format) with the
  device's access key (a QR code from `luna-web add-device` can carry it).
- **Conversation:** join the room, publish the microphone, play the agent, show its transcript.
- **Attributes:** send `luna.trigger`, and later `luna.wake_word` / `luna.say`, as described in
  [`../docs/protocol.md`](../docs/protocol.md).
- **Later:** on-device wake words, and a control-room connection so Home Assistant can make the phone "call".

## Notes

- An earlier standalone project (HAWakeWord, wake word to Home Assistant event) was tried here and removed: it is the wrong
  kind of app. It is still in this repository's git history.
- A local copy of the starter on drive `G:` was unreadable (hardware read errors on that disk), so the plan is to start from
  the public repository instead. Not downloaded yet.
- `livekit-wakeword` (used to train the `ron` / `rulon` models) is also public, if more custom wake words are needed.
