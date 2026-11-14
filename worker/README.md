# worker

The Luna worker: a [LiveKit Agents](https://docs.livekit.io/agents/) voice assistant for a senior at home, plus the token
service (`luna.web`) that lets devices join. It answers questions, controls Home Assistant through a fixed set of tools,
and notifies caregivers. Run the commands below from this folder; the `.env` lives at the repository root.

```
cp ../.env.example ../.env                        # provider keys; HA_URL / HA_TOKEN are optional for a first try
uv sync --extra openai --extra deepgram --extra cartesia
uv run python -m livekit.agents download-files
uv run python -m luna.agent console               # talk through this computer's microphone and speakers
uv run pytest                                      # (uv sync --group dev first) the tests
```

Deployment (compose, Kubernetes, NixOS) is in [`../docs/deployment.md`](../docs/deployment.md); the protocol with devices is in
[`../docs/protocol.md`](../docs/protocol.md).

## Configuration: `config.yaml`

| Section | Controls |
|---|---|
| `stt`, `llm`, `tts` | providers: `plugin:` is any `livekit-plugins-*` package (install its extra), the rest are its settings |
| `turns` | turn-taking tuned for slow, pausing speech; interruptions |
| `session` | hang up after `idle_seconds` of silence; the goodbye line |
| `wake_words` | what Luna says when each wake word starts a conversation (`say`, `reply`, `instructions`) |
| `thinking_sound` | the sound while she thinks (a built-in clip) |
| `home_assistant`, `devices`, `fan`, `sensors`, `doors`, `scripts` | what she may control or read: an allowlist, nothing else |
| `harmony`, `live_tv` | the TV: activities (with the start-up delay), cable favourites by number, the hardcoded Live TV channels |
| `weather` | weather from Open-Meteo (no key, not via Home Assistant): home by default, or any town by name |
| `web_search` | `web_search` tool: `brave`, `tavily`, `searxng` (a search API Luna calls) or `native` (the LLM's own search; needs `plugin: openai.responses`). Results are handed to the model as data. Not configured = no tool. |
| `caregivers` | who is notified (Home Assistant notify services); rate limit; urgent bypasses it |
| `agent` | her name and instructions |

Secrets are never in this file: it names environment variables (`token_env: HA_TOKEN`) and the values go in `.env`.

## Safety

- **Allowlist only:** she can do only what the config lists, with value limits (brightness, temperature, volume). Doors are look-only.
- **Dry run:** `LUNA_DRY_RUN=1` (the default in `.env.example`, Compose, Kubernetes and the NixOS module; the worker acts when the variable is unset) reads state but switches nothing and sends no notifications.
- **Audit log:** every action is written as JSON lines (`LUNA_AUDIT_LOG`).
- **Honest about failures:** a tool that fails says so; she never claims success without a tool result.
- At startup the config is checked against the live Home Assistant and problems are logged (she still starts).

## Status

Verified: the tools against fake Home Assistant, weather and TV services; console mode with real provider keys (a spoken
conversation with a door-status tool call); the token service (auth, throttling, server-chosen rooms, attributes). Not yet:
a conversation through LiveKit, a real Home Assistant action (everything has been dry-run), the Harmony start-up timing,
the cable favourites beyond those listed, the Docker image, the web UI in a browser.

## Widgets, referrals

`refer_to` points the person to a pharmacy, a helpline, a website: on a device with a screen (it set `luna.ui`) it shows a card
(phone, address, a button that opens the link); on a voice-only device the model reads it out. `show_widget` / `close_widget` let
the model show other A2UI surfaces on screen devices. See [`../docs/protocol.md`](../docs/protocol.md#widgets-proposed). Not yet run
against a real LiveKit room or client; the tests cover the validation and the messages sent.

## Timers and reminders

`set_timer`, `set_reminder`, `list_reminders`, `cancel_reminder`. They are kept by the token service (`python -m luna.web`) so they
outlive the conversation, and delivered by calling the device back through its control room. The agent finds the service at
`$LUNA_WEB_URL` (compose, Kubernetes and the NixOS module set it); set `timezone:` in `config.yaml`. Details and what a device must do:
[`../docs/protocol.md`](../docs/protocol.md#reminders). Not yet run against a real LiveKit server: the tests cover the store, the
scheduler, the internal HTTP API and the tools, with the LiveKit delivery replaced by a fake.
