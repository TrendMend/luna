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

## Commands (run from the folder shown)

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
