# AGENTS.md

Guidance for AI coding agents (and people) working in this repository. `README.md` explains what Luna is; this file is how to change it
without breaking it.

## Layout

| Path | What | Language |
|---|---|---|
| `worker/` | LiveKit agent (`src/luna/agent.py`), tools (`tools.py`), Home Assistant client and allowlists (`ha.py`), token and reminder service (`web.py`, `reminders.py`), A2UI widgets (`ui.py`) | Python 3.11-3.13, uv |
| `listener/` | Device endpoint: wake words, calls, LiveKit audio (`src/luna_listener/`) | Python, uv |
| `web/` | Browser UI (`index.html`, `a2ui.js`, `a2ui.css`) | plain JS, no build step |
| `android/` | Android app, package `dev.luna.assistant` | Kotlin, Compose, Gradle (JDK 17) |
| `docs/protocol.md` | The contract between the parts. Change it in the same commit as any protocol change. | |
| `deploy/`, `docker-compose.yml`, `flake.nix`, `nix/` | Kubernetes, Compose, Nix | |

The parts talk only over LiveKit (`docs/protocol.md`). `worker/config.yaml` is the documented set of defaults; deployments override it.

## Commands (run from the folder shown; also in `docs/development.md`)

```sh
# worker: the extras are required, config.yaml names those providers and the tests import them
cd worker   && uv run --extra openai --extra deepgram --extra cartesia --extra google --group dev pytest -q
cd listener && uv run --group dev pytest -q                  # add --extra wakeword to run the 3 tests that need openwakeword
node --test web/test
uvx ruff check .                                             # from the repository root (ruff.toml: syntax errors and pyflakes)
python scripts/fetch_wakeword_models.py                      # once, before any Android build or test
cd android  && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI (`.github/workflows/ci.yml`) runs the same checks plus a gitleaks scan and a trivy SBOM and vulnerability scan. All of them must pass.
Android lint has no baseline: do not add one to make a failure go away.

## Setting Luna up for a user

People will ask you to install, run or connect Luna ("set this up for me", "make it work on my phone"). Do it for them; this is the
procedure. Do not guess the pieces that only they can supply.

1. **Check the tools:** `docker --version`, `docker compose version`, `python --version` (3.11+). If one is missing, tell the user what to
   install and where from, and wait. Do not install system software without asking.
2. **Create `.env`:** copy `.env.example` to `.env` (if `.env` already exists, keep it and ask before changing it). Generate two random values with
   `python -c "import secrets; print(secrets.token_hex(32)); print(secrets.token_urlsafe(24))"` (or `openssl rand -hex 32` and `openssl rand -base64 24`).
   Set `LIVEKIT_API_SECRET` to the first and `LUNA_ACCESS_KEYS=laptop:<second>` (the key must be 16+ characters; do not leave the `change-me`
   placeholders). The setup link is `http://localhost:8080/#key=<second>`.
3. **Ask the user for what is theirs:** the provider API keys named in `worker/config.yaml` (by default `DEEPGRAM_API_KEY`,
   `OPENAI_API_KEY`, `CARTESIA_API_KEY`; `GOOGLE_API_KEY` for Gemini Live, with `LUNA_EXTRAS` including `google`), and, only if they
   want Luna to control the house, `HA_URL` and `HA_TOKEN`. Write them into `.env`. Never print, log or commit secrets, and never put
   them in a file other than `.env`.
4. **Start:** `docker compose up --build` (add `-d` to keep it running). The first build downloads speech models and takes a few minutes.
5. **Check:** `curl http://localhost:8080/healthz` returns `{"ok": true, ...}`; `docker compose logs agent` shows the worker registered.
   Give the user the setup link from step 2 and tell them to press Talk and allow the microphone.
6. **Keep it safe:** leave `LUNA_DRY_RUN=1` until the user has tried it and asks for real actions. Setting it to `0` lets Luna switch
   devices and send caregiver notifications; do that only when they say so, and tell them which devices are on the allowlist in
   `worker/config.yaml`.

Variations: a phone or tablet needs `LIVEKIT_NODE_IP` (the computer's LAN address) and `LIVEKIT_PUBLIC_URL` (`ws://<that address>:7880`,
or `wss://` behind TLS) in `.env`, and browsers need HTTPS for the microphone off `localhost` (`docs/deployment.md`). More devices:
`docker compose run --rm web python -m luna.web add-device <name> --url http://<address>:8080`, append the printed entry to
`LUNA_ACCESS_KEYS`, restart `web`. The Android app: `android/README.md` (build, or an MDM, `android/MDM.md`). Kubernetes and NixOS:
`docs/deployment.md`. If a step fails, read the logs, fix the cause, and report what you changed; do not work around a failure by
turning safety settings off.

## Rules that are easy to break

- **Say only what is true.** README, CHANGELOG, TODO and docs state what has been run. Do not claim device, MDM or production testing you
  did not see. Where something is untested, say so. The maintainer's own report of production use is recorded as a report, not as test results.
- **No approval step exists in the worker.** Tools act when called. The boundary is the allowlist in `worker/config.yaml` (devices,
  fan, TV, doors) and dry-run mode (`LUNA_DRY_RUN=1`). Doors are read-only by design: never add a tool that locks, unlocks or opens one
  without the owner's explicit decision. Describe safety behavior only as the code does it (`SECURITY.md`).
- **Every tool action is audited** (`Audit.write`). A new tool needs an allowlist check, an audit line, and a test with fakes (no network).
- **Secrets never go in the repository.** Use environment variables and add new ones, with placeholder values, to `.env.example`. Test
  fixtures use obviously fake keys (the gitleaks allowlist covers `tests/`).
- **No private details in tracked files:** no real addresses (use `192.168.1.50`), names of people or hostnames of private machines.
- **The openWakeWord models are CC BY-NC-SA and are not stored in git.** Do not commit the six files that
  `scripts/fetch_wakeword_models.py` downloads (they are git-ignored). `ron.onnx` and `rulon.onnx` are the project's own and are tracked.
- **One version:** the `VERSION` file. Change it only with `python scripts/bump_version.py` (see `docs/releasing.md`).
- **Fixtures compared byte for byte** (see `.gitattributes`): `worker-widgets.jsonl` is kept in step with the worker by
  `worker/tests/test_android_fixture.py`; the wake-word `golden.json` is made by `listener/tools/make_wakeword_golden.py`. Keep LF endings there.
- **UI is Material 3 everywhere.** Android uses `MaterialTheme` (`ui/theme/`); the web page uses the same colour roles as `--md-sys-*` tokens in `web/a2ui.css`. Use the tokens, not new colours or pixel values.
- **Android:** `minSdk` is 26. Guard newer APIs with `Build.VERSION.SDK_INT` (see `LockScreen.kt`); lint fails the build on a missing guard.
  The UI is stateless composables with previews in `app/src/screenshotTest/`; after a UI change run `:app:updateDebugScreenshotTest`,
  look at the images, and commit the updated references.
- **Protocol changes:** update `docs/protocol.md`, the worker, the Android app and the web page together, plus a test for each side.

## Style and workflow

- Match the surrounding code. Comments explain why, not what. Keep functions small and tested with fakes, as the existing tests do.
- One logical change per commit, imperative message in the style of `git log` (`ci: tests and linters`, `docs: security model and reporting`),
  tests passing at each commit. Do not rewrite or squash history that has been pushed, and never force-push. Ask the owner before any
  history rewrite, and before changing repository visibility.
- Update `CHANGELOG.md` (the `[Unreleased]` section) for anything a user would notice, and `TODO.md` when an open item is done or found.
- Working-copy files may use CRLF on Windows (`core.autocrlf`). When editing with scripts, preserve each file's line endings.
- `CLEANUP_PLAN.md`, if present, is the owner's private checklist for the public release and is not committed.
