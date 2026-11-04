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

## Ending a conversation

The agent leaves the room after `session.idle_seconds` of nobody talking, or when the person says goodbye
(`end_conversation`). Devices treat the agent leaving as the end and go back to idle.
