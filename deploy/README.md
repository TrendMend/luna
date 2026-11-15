# Deploy

Ready-made ways to run the server side of Luna (LiveKit server, the agent, and the token service with the web page). Pick one; the full guide, including
what to set and how to check it, is [`../docs/deployment.md`](../docs/deployment.md).

| Path | What it is |
|---|---|
| [`../docker-compose.yml`](../docker-compose.yml) | The easiest route: one command starts LiveKit, the agent and the web service on one machine. This is the one the README's quick start uses. |
| [`compose/livekit.yaml`](compose/livekit.yaml) | The LiveKit server configuration the Compose stack mounts. The API key and secret come from `.env`. |
| [`kubernetes/`](kubernetes/) | Kustomize manifests for the agent and the web service, with an ingress and a volume for state. LiveKit's media server is **not** in them: install LiveKit's official Helm chart (example values in `livekit-values.example.yaml`). |
| [`../nix/`](../nix/) and [`../flake.nix`](../flake.nix) | A Nix package and NixOS modules (`services.luna`, `services.luna-listener`). |

## Kubernetes in short

```sh
kubectl create namespace luna
kubectl -n luna create secret generic luna-secrets --from-env-file=.env
kubectl -n luna create configmap luna-config --from-file=config.yaml=worker/config.yaml
kubectl apply -k deploy/kubernetes
```

Change the image name in `kustomization.yaml` (`ghcr.io/CHANGE-ME/luna-agent`) to a registry you push the worker image to. `secret.example.yaml` shows the
variables the secret needs. Dry-run is on in the example secret (`LUNA_DRY_RUN: "1"`); set it to `0` only when you want Luna to act.

## What has been checked

The Compose stack was built and started from a clean copy on 2026-11-13 with no provider keys: the containers came up healthy, the web page and token
endpoint worked, and the agent registered with LiveKit (details in `docs/deployment.md`). The Kubernetes manifests were validated statically
(`kustomize build`, `kubeconform`) and have not been applied to a cluster by me. The maintainer reports the deployment working in production.
