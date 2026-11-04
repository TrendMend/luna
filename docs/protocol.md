# Luna protocol

Every part of Luna talks over **LiveKit** and nothing else. This page is the contract between the
worker, the devices (web, Android, listener) and Home Assistant. Anything not listed here is not part of it.

## Rooms

| Room | Who joins | Audio | Agent |
|---|---|---|---|
| `luna-<device>-<random>` | one device, then the agent | yes, both ways | dispatched by the token (`luna` agent) |
| `luna-control-<device>` | one device (its standing connection) and Home Assistant (server API) | none | none |

A conversation room is fresh for every conversation. The control room is long-lived and only carries
data messages.

## Tokens (the only HTTP call)

`POST /api/token` on the worker's token service follows LiveKit's standard token-endpoint format
(`Authorization: Bearer <device access key>`). Add keys with `luna-web add-device <name>`.

Request fields Luna reads:

| Field | Meaning |
|---|---|
| `purpose` | `"control"` for the standing control-room token. Omit for a conversation. |
| `participant_attributes` | the attributes below. Anything else is dropped. |
| `participant_name` | display name |

Everything else a client sends (room name, `room_config`, ...) is ignored: the server picks the room and
dispatches the agent.

## Participant attributes (device -> agent)

| Attribute | Set by | Meaning |
|---|---|---|
| `luna.device` | server | which device this is. Cannot be spoofed. |
| `luna.wake_word` | device | id of the wake word heard (`[a-z0-9_]{1,40}`); picks an entry under `wake_words:` in `worker/config.yaml` |
| `luna.say` | device | a phrase to open the conversation with (<= 300 printable chars); beats the wake word's own phrase |
| `luna.trigger` | device | what started it: `wake_word`, `call`, `button`, ... (informational) |

## Calls from Home Assistant (data message -> device)

Home Assistant sends a LiveKit data message with the server API (`SendData`) to the control room of the
device it wants to wake:

- topic: `luna.call`
- payload (JSON): `{"say": "optional opening phrase"}`

The listener then starts a conversation the same way it does for a wake word, with `luna.trigger=call`
and `luna.say` set from the payload.

## Audio tracks

In a conversation room the device publishes one microphone track. The agent publishes two: `roomio_audio` (its speech) and
`background_audio` (the thinking sound, only while it is working). A device that has one speaker buffer should play
`roomio_audio` and either skip `background_audio` or mix it in separately; adding both into one queue chops the speech up.

## Ending a conversation

The agent always says goodbye, then leaves the room. It does so after `session.idle_seconds` of nobody talking (it says
`session.goodbye`), after `session.max_seconds` in total (same), or when the person says goodbye (the model says its own;
`session.goodbye` is the fallback if it says nothing). Devices treat the agent leaving as the end and go back to idle; the
listener's own `session.max_seconds` is only a backstop for a worker that has stopped responding.
