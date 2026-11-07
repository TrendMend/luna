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
| `luna.ui` | device | the widget catalog ids this device can render, comma separated (see [Widgets](#widgets-proposed)). Absent = voice only, no widgets. |

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

## Widgets (proposed)

**Status: proposed, not implemented.** Luna uses [A2UI](https://a2ui.org) (Google's agent-to-UI protocol) for widgets instead of
inventing a format. The agent never sends code: it sends JSON that names components from a catalog the device has agreed to
render, and the catalog is the trust boundary. LiveKit carries it; A2UI is transport-agnostic.

**Pinned version: A2UI v0.9.1.** That is what the official Android renderer (`androidx.a2ui.compose`, `1.0.0-alpha02`)
supports. The A2UI site now lists v1.0 as current; moving up is a deliberate change here, not a drift.

### Who can show widgets

Only a device that sets `luna.ui` (a participant attribute, comma-separated catalog ids). A voice-only device (the listener)
does not set it, and the worker does not offer the widget tool to the model. The worker only uses a catalog id the device
listed. Catalog ids are opaque strings that must match exactly. Luna's two:

| Catalog id | What |
|---|---|
| `https://a2ui.org/specification/v0_9_1/catalogs/basic/catalog.json` | the A2UI basic catalog (Text, Image, Icon, Row, Column, List, Card, Tabs, Divider, Modal, Button, CheckBox, TextField, DateTimeInput, ChoicePicker, Slider, ...) |
| `https://luna.invalid/catalogs/home/v1/catalog.json` | placeholder id for Luna's own components (Home Assistant light, thermostat, confirm/deny, ...). Replace the host before anything ships. |

### Agent -> device: `luna.ui` text stream

The agent sends A2UI server-to-client messages (`createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface`)
on a LiveKit **text stream** with topic `luna.ui`, one stream per batch, JSON Lines (one message per line, each carrying
`"version": "v0.9.1"`). A text stream is reliable and in order, which A2UI requires. Example:

```jsonl
{"version":"v0.9.1","createSurface":{"surfaceId":"fan","catalogId":"https://a2ui.org/specification/v0_9_1/catalogs/basic/catalog.json"}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"fan","components":[{"id":"root","component":"Column","children":["t","b"]},{"id":"t","component":"Text","text":"Turn the fan on?"},{"id":"b","component":"Button","child":"bl","variant":"primary","action":{"event":{"name":"confirm","context":{}}}},{"id":"bl","component":"Text","text":"Yes"}]}}
```

Rules from A2UI worth repeating: one component must have id `root`; `surfaceId` and `catalogId` are fixed for a surface's
life; a bad message is skipped and the rest still apply. Surfaces belong to the conversation: the device clears them when
the agent leaves the room.

### Device -> agent: `luna.ui.action` text stream

When the person taps something, the device sends one A2UI `action` message on a text stream with topic `luna.ui.action`:

```json
{"version":"v0.9.1","action":{"name":"confirm","surfaceId":"fan","sourceComponentId":"b","timestamp":"2026-11-06T12:00:00Z","context":{}}}
```

`name`, `surfaceId`, `sourceComponentId`, `timestamp` (ISO 8601) and `context` are all required. The worker hands the action
to the model as a turn the person took ("pressed confirm on surface fan"), so the conversation continues by voice or widget.
Typing into a field sends nothing by itself; values travel in an action's `context`. A device that cannot validate a message
sends `{"version":"v0.9.1","error":{"code":"VALIDATION_FAILED","surfaceId":"...","path":"...","message":"..."}}` on the same topic.

### Open questions

- The A2UI pages disagree on the shape of `a2uiClientCapabilities` (flat, or nested under a version key). Luna sidesteps it with
  the `luna.ui` attribute; check the JSON schemas in the A2UI repository if full A2A-style negotiation is ever wanted.
- The Android renderer docs do not show how a JSONL string is fed to `A2uiMessageProcessor`, or how a dispatched action
  leaves the device (`ProvideActionInterceptor` is the likely hook). Read the API reference when the Android app exists.
- The worker's token service drops unknown attributes, so allowing `luna.ui` is a code change in `worker/src/luna/opening.py`
  and the token endpoint.
