# Luna

A voice assistant for a senior at home: it answers questions, controls Home Assistant (lights, TV, fan, doors, temperatures) and
notifies caregivers. Everything runs over [LiveKit](https://livekit.io).

| Folder | What | Status |
|---|---|---|
| [`worker/`](worker/) | the LiveKit agent (speech in, model, tools, speech out) and the token service | working in console mode; not yet run end to end against LiveKit |
| [`web/`](web/) | browser UI / Android home-screen app | written, untested in a browser |
| [`listener/`](listener/) | device endpoint: wake words and calls from Home Assistant | core written and tested with fakes; not yet run against LiveKit |
| [`android/`](android/) | Android app | not started |
| [`homeassistant/`](homeassistant/) | Home Assistant extension (make Luna call a device) | not started |
| [`deploy/`](deploy/), [`docker-compose.yml`](docker-compose.yml) | Kubernetes and compose | validated statically, not run |
| [`flake.nix`](flake.nix), [`nix/`](nix/) | Nix package and NixOS module (`services.luna`) | builds; service not run yet |
| [`docs/`](docs/) | [the LiveKit protocol](docs/protocol.md) that ties the parts together | |

Start with [`worker/README.md`](worker/README.md).
