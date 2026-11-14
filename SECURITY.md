# Security

This page describes what the code does today for safety, and where it stops. It is not a guarantee, and Luna has not had an
independent security review. Where something is only a configuration or a model instruction, it says so.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's private vulnerability reporting on this repository
(Security tab, "Report a vulnerability"). Include what you found, how to reproduce it and which version (`VERSION`) you ran. This is a
small personal project: there is no service-level promise, but reports will be read and answered.

## What the assistant can do (tools)

The model can only act through the tools in `worker/src/luna/tools.py`. There is no shell, file or general network tool.

| Tool | Effect |
|---|---|
| `control_device`, `control_fan` | Home Assistant service calls, limited to the entities listed in `worker/config.yaml` (`devices:`, `fan:`) and to the actions listed for each. A name or action outside the list is refused. Brightness is range-checked. |
| `tv_activity`, `tv_button`, `cable_channel`, `live_tv_channel`, `show_on_tv`, `remember_cable_channel` | Switch the TV setup, press remote buttons, change channels, from lists in the config |
| `check_doors`, `get_readings`, `get_weather` | Read only. **Doors are read only**: the code has no way to lock, unlock or open one. |
| `web_search`, `refer_to` | A web search through the configured provider; show a referral card |
| `set_timer`, `set_reminder`, `list_reminders`, `cancel_reminder` | Reminders kept by the token service and delivered back to the device |
| `show_widget`, `show_card`, `show_list`, `show_choices`, `close_widget` | Draw A2UI widgets on a screen device (validated before they are sent) |
| `notify_caregivers` | Send a push notification through the Home Assistant `notify` services named in the config. An urgent one also runs the configured `urgent_script`. Non-urgent messages are rate limited (default one per 120 s). |
| `end_conversation` | Hang up |

**There is no human approval step in the worker.** If the model calls `control_device`, the call is made. A widget with "Yes" and "No"
buttons (such as the confirmation card in the screenshots) is something the model can choose to show and wait for, but the code does not
require it. The `on_duplicate="confirm"` setting on some TV tools is LiveKit's guard against the same call being repeated; it is not a
question to the user. The only built-in brake on actions is **dry-run mode**: with `LUNA_DRY_RUN=1` Home Assistant is read but no service
call is made and no notification is sent. The `.env.example`, Compose file, Kubernetes example and NixOS module all default to dry-run.
The worker itself acts when the variable is unset, so a deployment that omits it is not in dry-run.

Every tool action is appended as a JSON line to the audit log (`LUNA_AUDIT_LOG`, default `logs/audit.jsonl`), including the messages sent
to caregivers. Tool failures are returned to the model as errors with an instruction not to claim success.

Because the model chooses the tool calls, the allowlist in `config.yaml` is the real boundary. Keep it to what you are willing for a
misled or mistaken model to do, and keep `LUNA_DRY_RUN=1` until you have checked the configuration.

## Access keys and tokens

- Each device has an access key in `LUNA_ACCESS_KEYS` (`name:key`, comma separated, at least 16 characters). `POST /api/token` requires
  it as a Bearer token and compares it in constant time against every key.
- Ten failed attempts from one address within a minute are refused with HTTP 429 (the counter is in memory and per address).
- The server chooses the room and dispatches the agent; the room and room configuration a client sends are ignored. `luna.device` is set by
  the server and cannot be spoofed. Other participant attributes come from the client but are filtered to the ones in `docs/protocol.md`.
- A conversation token can publish audio and data to its own fresh room. The standing "control room" token can neither publish nor
  subscribe, so a device cannot send itself a `luna.call`; only a holder of the LiveKit server API credentials can (Home Assistant).
- Revoking a device means deleting its entry and restarting the token service. Tokens already issued stay valid until they expire
  (`TOKEN_TTL_SECONDS` in `worker/src/luna/web.py`).
- Reminder routes between the worker and the token service (`/api/internal/reminders`) use a bearer token derived from the LiveKit API
  secret with HMAC-SHA256.
- Secrets (`HA_TOKEN`, provider keys, LiveKit keys, access keys) are read from the environment. `.env` is git-ignored, and the CI secret scan
  checks the repository history.

## Known limits

- **Plain http.** The token service speaks http. Anyone on the same network who can see the traffic can read an access key. It is meant
  for a home LAN; put HTTPS in front of it before using it anywhere else (browsers also require HTTPS for the microphone off `localhost`).
- **Android app: cleartext allowed.** `usesCleartextTraffic="true"` is set in the manifest so it can reach a LAN address over http. The
  access key is kept in the app's private storage (`SharedPreferences`), not the Android Keystore (an open item in `TODO.md`). Backups are
  off (`allowBackup="false"`).
- **No authorization levels.** Any device with a valid key can start a conversation and use every tool; there are no per-device
  permissions or per-person approvals.
- **A voice is not an identity.** Anyone who can speak to a device can ask it to do anything on the allowlist, including sending a
  message to caregivers. Wake words are not a security control.
- **Audio and text go to the providers you configure** (speech, language model, search). Audio stays on the device until a wake word,
  a button press or a call starts a conversation; after that it is sent to the LiveKit server and on to those providers.
- **The in-memory throttle** resets when the token service restarts and does not cover distributed attempts.
- **Untested in real deployments:** see the "not yet verified" list in the README. In particular nothing here has been tested against a
  real MDM, and the NixOS, Kubernetes and Docker deployments have not been run end to end.
