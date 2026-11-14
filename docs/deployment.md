# Deploying Luna

Luna is four parts: a **LiveKit server** (carries audio), the **worker** (the assistant, plus the token service),
a **listener** on each device people talk to, and the **web UI / Android app** for phones and tablets. How they talk is in
[`protocol.md`](protocol.md). Pick how to run the server-side parts; the listener is installed on the device.

**Safety first.** Everything starts in dry-run mode (`LUNA_DRY_RUN=1`): the worker reads Home Assistant but switches
nothing and sends no notifications. Set it to `0` only when you are ready.

**What has been run:** the unit tests (counts in `development.md`) use fake Home Assistant, weather and TV services. The earlier notes
record the worker in console mode with real Deepgram, OpenAI and Cartesia keys, the listener's wake-word detection on a real
microphone (`hey jarvis`), and Nix builds of both Python projects. The maintainer reports (2026-11-13) that the whole system has been confirmed working in production use. No test logs or per-feature records back that report; it is the owner's statement.

## Settings

| Where | What |
|---|---|
| `worker/config.yaml` | providers (speech-to-text, model, voice), turn-taking, devices, TV, doors, caregivers, wake-word greetings, idle hang-up |
| `listener/config.yaml` | the device name, token service, audio, wake words (on/off, threshold), triggers |
| `.env` (secrets, never committed) | `LIVEKIT_*`, `HA_URL` / `HA_TOKEN`, `LUNA_ACCESS_KEYS`, the provider keys (`DEEPGRAM_API_KEY`, ...). A template is `.env.example`. |

**Per-deployment settings.** The two `config.yaml` files are the documented defaults; a deployment changes only what differs.
Any service can read an overrides file (YAML or JSON) from `$LUNA_CONFIG_OVERRIDES` (worker) or `$LUNA_LISTENER_OVERRIDES`
(listener). Nested mappings merge key by key, lists are replaced, and a top-level name that is not in the defaults is an error
(so a typo cannot silently do nothing). On NixOS, `services.luna.settings` and `services.luna-listener.settings` write that file
for you (example below).

Each device gets an access key: `luna-web add-device <name> --url <where it opens the UI>` prints the entry for
`LUNA_ACCESS_KEYS` and a QR code.

## 1. Locally (to try it)

```
cd worker
cp ../.env.example ../.env                        # fill in the provider keys; the .env lives at the repo root
uv sync --extra openai --extra deepgram --extra cartesia
uv run python -m livekit.agents download-files    # one-time: speech-detection models
uv run python -m luna.agent console               # talk through this computer's microphone and speakers; no LiveKit needed
```
Ctrl+C can be unreliable on Windows in console mode (LiveKit deprecates it); close the terminal or use Ctrl+Break.

For the full path (browser or listener), run a LiveKit server (`docker compose up livekit`, or `livekit-server --dev`),
then `uv run python -m luna.agent dev` and `uv run python -m luna.web` in `worker/`.

## 2. Docker Compose

```
cp .env.example .env             # then set LIVEKIT_API_SECRET and LUNA_ACCESS_KEYS to fresh random values and add your provider keys (README, Quick start)
docker compose up --build        # LiveKit + worker + token service/web UI; open http://localhost:8080
```
Uses `worker/Dockerfile` with the repository root as the build context. Provider plugins are build arguments (`LUNA_EXTRAS`).
*Validated with `docker compose config` and the Dockerfile with hadolint; On 2026-11-13 a clean copy was built and started this way with no provider keys: the three containers came up healthy, `/healthz`, the web page, the icon and the manifest answered, the token endpoint gave 401 without or with a wrong key and a token with the generated key, and the agent registered with LiveKit. A conversation needs real provider keys and was not part of that check. Also reported working in production (see above).*

## 3. Kubernetes

Manifests are in `deploy/kubernetes` (kustomize): the worker, the token service/web UI with an ingress, a volume for state.
LiveKit's media server is **not** in them: install LiveKit's official Helm chart (example values:
`livekit-values.example.yaml`; it needs host networking and TLS at the ingress).
```
kubectl create namespace luna
kubectl -n luna create secret generic luna-secrets --from-env-file=.env
kubectl -n luna create configmap luna-config --from-file=config.yaml=worker/config.yaml
kubectl apply -k deploy/kubernetes
```
*`kustomize build` and `kubeconform` pass; nothing has been applied to a cluster.*

## 4. NixOS

The flake at the repository root provides:

| Output | What |
|---|---|
| `packages.<system>.default` | the worker's Python environment (built from `worker/uv.lock`) |
| `packages.<system>.listener` | the listener's environment (`listener/uv.lock`) |
| `nixosModules.default` | `services.luna`: the worker (`luna-agent`) and optionally the token service/web UI (`luna-web`) |
| `nixosModules.listener` | `services.luna-listener`: a systemd **user** service for the desktop user that owns the audio |

```nix
inputs.luna.url = "github:<you>/luna";                  # or wherever this repository is hosted
# ...
modules = [ luna.nixosModules.default luna.nixosModules.listener ./luna.nix ];
```
```nix
# luna.nix
{
  services.luna = {
    enable = true;
    environmentFile = "/var/lib/luna/env";   # secrets, one KEY=value per line; create it by hand, mode 600
    dryRun = true;                           # until you are ready
    web.enable = true;                       # token service for the listener and phones
  };
  services.luna-listener = {
    enable = true;
    user = "kiosk";                          # the user whose PipeWire session owns the microphone and speaker
    environmentFile = "/var/lib/luna-listener/env";   # LUNA_ACCESS_KEY=...  (from: luna-web add-device nuc)
    # optional: a fixed microphone and speaker (ALSA device names, see listener/README.md), and whether the listener
    # does its own echo cancellation (turn it off when the system already does)
    # inputDevice = "luna_mic"; outputDevice = "luna_speaker"; echoCancellation = false;
    settings.wake_words.ron.threshold = 0.7;  # this house's tuning, merged over listener/config.yaml
  };
}
```
The listener downloads openWakeWord's built-in models on first start into its state directory (the Nix package is read-only).
Custom wake-word models (`ron.onnx`, `rulon.onnx`) ship in `listener/models/`.
*The Python environments build and import on Linux and the module evaluates; the services are reported running in production (see above).*

## Troubleshooting

- **Speech is slow, stretched or choppy:** read the listener's `playback stats` line when the conversation ends
  (`listener/README.md` explains each field). Zero underruns and device underflows with a normal `frames_in` means the
  speaker side is fine and the problem is in the audio it was given.
- **Nothing is said at the start of a call, or only after several seconds:** the worker could not reach a provider quickly. Look
  for `failed to connect to deepgram` / `Request timed out` in the worker log. A host whose DNS answers with IPv6 addresses
  but has no IPv6 route waits for each connection to fail first; prefer IPv4 when sorting addresses, or fix the route.
- **No sound from the right device:** check which device each stream is linked to (`wpctl status`), see "Microphone and
  speaker" in `listener/README.md`.

## Security notes

- **Access keys:** one per device, 16+ characters, compared in constant time, 10 failures a minute per address are throttled.
  Revoke a device by deleting its entry.
- **The server picks the room and the agent;** whatever a client sends for those is ignored. Devices can set only the
  attributes in `protocol.md`, and `luna.device` is always set by the server.
- **Calls from Home Assistant** travel as LiveKit data messages that only the server API can send into a device's control room.
- **HTTPS:** browsers allow the microphone only on `https://` or `localhost`. A phone on your network needs HTTPS in front of the
  web UI (Tailscale, a domain with a certificate, or your own certificate authority); the listener on the NUC does not.
- **Audio never leaves a listener until a wake word (or a call) starts a conversation.**
