# Deploying Luna

Luna is four parts: a **LiveKit server** (carries audio), the **worker** (the assistant, plus the token service),
a **listener** on each device people talk to, and the **web UI / Android app** for phones and tablets. How they talk is in
[`protocol.md`](protocol.md). Pick how to run the server-side parts; the listener is installed on the device.

**Safety first.** Everything starts in dry-run mode (`LUNA_DRY_RUN=1`): the worker reads Home Assistant but switches
nothing and sends no notifications. Set it to `0` only when you are ready.

**What has actually been run** (so you know what to trust): the worker's tools against fake Home Assistant / weather
services (59 tests), the worker in console mode with real Deepgram, OpenAI and Cartesia keys, the listener's wake-word
detection on a real microphone (`hey jarvis`), and Nix builds of both Python projects. **Not yet run end to end:** a
conversation through LiveKit, the container image, the Kubernetes manifests, the NixOS services, echo cancellation,
the web UI in a browser.

## Settings

| Where | What |
|---|---|
| `worker/config.yaml` | providers (speech-to-text, model, voice), turn-taking, devices, TV, doors, caregivers, wake-word greetings, idle hang-up |
| `listener/config.yaml` | the device name, token service, audio, wake words (on/off, threshold), triggers |
| `.env` (secrets, never committed) | `LIVEKIT_*`, `HA_URL` / `HA_TOKEN`, `LUNA_ACCESS_KEYS`, the provider keys (`DEEPGRAM_API_KEY`, ...). A template is `.env.example`. |

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
cp .env.example .env
docker compose up --build        # LiveKit + worker + token service/web UI; open http://localhost:8080
```
Uses `worker/Dockerfile` with the repository root as the build context. Provider plugins are build arguments (`LUNA_EXTRAS`).
*Validated with `docker compose config` and the Dockerfile with hadolint; the image has not been built or run.*

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

## 4. NixOS (the NUC)

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
  };
}
```
The listener downloads openWakeWord's built-in models on first start into its state directory (the Nix package is read-only).
Custom wake-word models (`ron.onnx`, `rulon.onnx`) ship in `listener/models/`.
*The Python environments build and import on Linux and the module evaluates; the services have not been started on the NUC.*

## Security notes

- **Access keys:** one per device, 16+ characters, compared in constant time, 10 failures a minute per address are throttled.
  Revoke a device by deleting its entry.
- **The server picks the room and the agent;** whatever a client sends for those is ignored. Devices can set only the
  attributes in `protocol.md`, and `luna.device` is always set by the server.
- **Calls from Home Assistant** travel as LiveKit data messages that only the server API can send into a device's control room.
- **HTTPS:** browsers allow the microphone only on `https://` or `localhost`. A phone on your network needs HTTPS in front of the
  web UI (Tailscale, a domain with a certificate, or your own certificate authority); the listener on the NUC does not.
- **Audio never leaves a listener until a wake word (or a call) starts a conversation.**
