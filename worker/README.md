# Worker

The server side of Luna. It is two programs from one package (`luna`):

| Program | Command | What it does |
|---|---|---|
| **Agent** | `python -m luna.agent` | A [LiveKit Agents](https://docs.livekit.io/agents/) voice assistant. It listens, thinks, speaks, and acts through a fixed set of tools (Home Assistant, TV, weather, search, reminders, caregiver messages). |
| **Token service** | `python -m luna.web` (also `luna-web`) | Gives each device a short-lived LiveKit token for its access key, serves the [web page](../web/), and keeps timers and reminders. |

## Try it

You need [uv](https://docs.astral.sh/uv/) and the provider API keys named in `config.yaml`. Run these from this folder; the `.env` lives at the
repository root (copy `../.env.example`; `HA_URL` and `HA_TOKEN` are optional for a first try).

```sh
uv sync --extra openai --extra deepgram --extra cartesia   # the provider plugins config.yaml uses
uv run python -m livekit.agents download-files             # one-time: speech-detection models
uv run python -m luna.agent console                        # talk through this computer's microphone and speakers
```

Console mode needs no LiveKit server. For the whole system (LiveKit, token service, devices) use Docker Compose, Kubernetes or NixOS:
[`../docs/deployment.md`](../docs/deployment.md). The wire protocol with devices: [`../docs/protocol.md`](../docs/protocol.md).

## Configuration

Everything is in [`config.yaml`](config.yaml). It is the documented set of defaults; a deployment overrides only what differs
(`LUNA_CONFIG_OVERRIDES`). Secrets are never in it: it names environment variables (`token_env: HA_TOKEN`) and the values go in `.env`.

| Section | Controls |
|---|---|
| `stt`, `llm`, `tts` | Providers. `plugin:` is any `livekit-plugins-*` package (install its extra); the other keys are its settings. A realtime model such as Gemini Live can replace all three. |
| `turns` | Turn-taking, tuned for slow, pausing speech; interruptions. |
| `session` | Hang up after `idle_seconds` of silence; the goodbye line; `language` and `language_only`. The languages Luna accepts are the top-level `languages:` list. |
| `server` | How many conversations run at once and the health-check port. |
| `timezone`, `person` | The time zone for reminders; the person's first name for notification titles. |
| `wake_words` | What Luna says when each wake word starts a conversation (`say`, `reply`, `instructions`). |
| `thinking_sound` | The sound while she thinks (a built-in clip). |
| `home_assistant`, `devices`, `fan`, `sensors`, `doors`, `scripts` | What she may control or read. An allowlist, nothing else. |
| `harmony`, `live_tv` | The TV: activities (with start-up delay), cable favourites by number, the Live TV channels. |
| `weather` | Weather from Open-Meteo (no key, not through Home Assistant): home by default, or any town by name. |
| `web_search` | The `web_search` tool through `brave`, `tavily`, `searxng` or `native` (the model's own search, which needs `plugin: openai.responses`). Results reach the model as data. Not configured means no tool. |
| `caregivers` | Who is notified (Home Assistant notify services), the rate limit, and urgent messages that bypass it. |
| `agent` | Her name and instructions. |

## Safety

- **Allowlist only.** She can do only what the config lists, with value limits (brightness, temperature, volume). Doors are look-only.
- **Dry run.** With `LUNA_DRY_RUN=1` she reads state but switches nothing and sends no notifications. It is the default in `.env.example`,
  Compose, Kubernetes and the NixOS module; the worker itself acts when the variable is unset.
- **Audit log.** Every action is written as a JSON line (`LUNA_AUDIT_LOG`).
- **Honest about failures.** A tool that fails says so; she does not claim success without a tool result.
- **Startup check.** The config is checked against the live Home Assistant and problems are logged (she still starts).

There is no per-action approval step. The full picture, and its limits, is in [`../SECURITY.md`](../SECURITY.md).

## Widgets and referrals

`refer_to` points the person to a pharmacy, a helpline or a website. On a device with a screen (one that set `luna.ui`) it shows a card with
the phone number, address and a button that opens the link; on a voice-only device the model reads it out. `show_widget`, `show_card`,
`show_list`, `show_choices` and `close_widget` let the model show other A2UI surfaces on screen devices.
See [`../docs/protocol.md`](../docs/protocol.md#widgets-proposed).

## Timers and reminders

`set_timer`, `set_reminder`, `list_reminders` and `cancel_reminder`. The token service keeps them, so they outlive the conversation, and
delivers them by calling the device back through its control room. The agent finds the service at `$LUNA_WEB_URL` (Compose, Kubernetes and
the NixOS module set it). Set `timezone:` in `config.yaml`. Details, and what a device must do:
[`../docs/protocol.md`](../docs/protocol.md#reminders).

## Tests

```sh
uv run --extra openai --extra deepgram --extra cartesia --extra google --group dev pytest -q
```

They use fakes for Home Assistant, weather, TV, search and LiveKit delivery, so they need no network or keys. More:
[`../docs/development.md`](../docs/development.md).

## Status

- **Checked directly:** the tools against fake Home Assistant, weather and TV services; console mode with real provider keys (a spoken
  conversation with a door-status tool call); the token service (auth, throttling, server-chosen rooms, attributes).
- **Reported:** the maintainer reports (2026-11-13) that the whole system works in production. No test logs back that report.
- **Open:** the Harmony start-up timing, and cable favourites beyond those listed.
