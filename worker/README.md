# worker

The Luna worker (LiveKit agent plus token service). Run the commands below from this folder. Voice assistant agent for the NUC, built on LiveKit Agents. Providers are chosen in `config.yaml`
(any `livekit-plugins-*` package); keys come from the environment.

```
cp ../.env.example ../.env     # (the .env lives at the repo root)   LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET + provider keys
uv sync --extra openai --extra deepgram --extra cartesia   # extras for the plugins in config.yaml
uv run python -m luna.agent download-files   # fetch VAD / turn-detector models
uv run python -m luna.agent dev              # connect to the LiveKit server and wait for a room
```
