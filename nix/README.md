# Nix

NixOS modules for running Luna as system services. They are exposed by the flake at the repository root (`flake.nix`).

| File | Option set | What it runs |
|---|---|---|
| [`module.nix`](module.nix) | `services.luna` | The worker's agent (`luna-agent`) and, optionally, the token service and web page (`luna-web`). |
| [`listener-module.nix`](listener-module.nix) | `services.luna-listener` | The listener, as a systemd **user** service for the desktop user that owns the microphone and speaker. |

The flake also builds the Python environments (`packages.<system>.default` for the worker, `.listener` for the listener) from the two `uv.lock` files.
`services.luna.dryRun` defaults to `true`. Per-deployment configuration goes in `services.luna.settings` and `services.luna-listener.settings`, which are merged
over the `config.yaml` defaults.

Example configuration and notes: [`../docs/deployment.md`](../docs/deployment.md#4-nixos). The Python environments build and import on Linux and the
modules evaluate. The maintainer reports the deployment working in production.
