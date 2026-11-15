# Changelog

All notable changes to Luna. The version is the one in the `VERSION` file ([semantic versioning](https://semver.org/): a fix is a patch, a
feature a minor, a change that needs people to do something a major; while it starts with 0 a minor may still change things). How a release is
made: `docs/releasing.md`.

## [0.3.0](https://github.com/TrendMend/luna/compare/v0.2.0...v0.3.0) (2026-11-15)


### Features

* **worker:** names in wake word replies, different words per device ([cc67d08](https://github.com/TrendMend/luna/commit/cc67d08a1bc6d5964724fdce9d945adfaf32a18c))


### Bug Fixes

* **android:** wake words come back after a restart on a hidden or kiosk device ([eb61362](https://github.com/TrendMend/luna/commit/eb61362a9d27c3dae1e075aff446ce104edc9074))
* **ci:** run the Android build and the web tests on the runners ([21cdc30](https://github.com/TrendMend/luna/commit/21cdc30c3a7a9152ca83629e2370308f41a7056c))

### Also in this release

#### Added

- **CI.** A Docker build and smoke test, CodeQL, weekly scheduled security scans, workflow hygiene (actions pinned to commits,
  timeouts, cancelling superseded runs).
- **Android releases.** Merging the release pull request (made by release-please) tags the release and builds the release APK, which is attached
  to it with a checksum (`docs/releasing.md`). The release build is signed from environment variables, so no key is in the repository.
- **Language.** Luna can be told which language to speak, and to speak only that one (Settings, an MDM's `language` and `language_only`, or
  the worker's `session.language` and `session.language_only`). The worker accepts only languages on its `languages:` list. The recorded
  English "Hi!" is skipped when another language is chosen. It is an instruction to the model, so it changes nothing about the free tier.

#### Changed

- **The web page is Material 3**, with the Android app's colour roles, light and dark by system setting, M3 buttons, outlined fields, dialog,
  tabs and cards, and an in-page dialog for the access key instead of the browser's prompt box. The home-screen icon and manifest colours now match the
  Android icon. `web/demo.html#confirm` opens a widget demo directly.

#### Fixed

- The Dockerfile now uses a numeric `USER` and a JSON-form healthcheck (hadolint).

#### Verified

- The maintainer reports (2026-11-13) that everything is confirmed working in production, including the two items under "Known gaps"
  for 0.2.0 (wake words on a real device, managed configurations on a real MDM). No test records are kept in the repository.

## [0.2.0] - 2026-11-07

First numbered release. Everything below is new since Luna moved to Gemini Live.

### Added

- **Gemini Live.** The conversation runs on the Gemini Live API (`gemini-3.8-live`, voice Aoede) in place of the separate speech-to-text,
  language model and text-to-speech stages, on the free tier.
- **Requests only a person can do** (coffee, dinner) are passed to a caregiver instead of the assistant pretending it can help.
- **Wake words on the Android app** (ron, rulon, alexa, hey jarvis, hey mycroft, hey rhasspy) through a microphone foreground service that opens
  Luna over what the person was doing. Each word has its own on/off switch and a button for its own sensitivity.
- **The assistant's name** (set in Settings) replaces "Luna" in notifications.
- **A tabbed weather card** like Google's: temperature, precipitation, wind and week, with an hourly strip that scrolls sideways.
- **A recorded greeting** ("Hi!", "Hello!", ...) that the phone plays the moment Luna opens, so the person knows it is listening.
- **Popup settings:** the greeting on or off, and the end button on or off (tapping outside the popup always closes it).
- **Android Enterprise managed configurations** (`android/MDM.md`): an admin can set the connection, names, calls, wake words and sensitivity,
  the greeting and the popup end button from an MDM, and can hide the Settings screen or the app icon.
- **A version** for the whole repository (this file, `VERSION`, `scripts/bump_version.py`), shown in the app's Settings and reported by the
  worker (`/healthz` and the conversation log).

### Changed

- The standing notification of the wake word service shows only the assistant's name, silently and not on the lock screen.
- More capacity for back-to-back conversations: two warm worker processes and up to three conversations at once.

### Fixed

- **The first greeting was lost** when the realtime model reconnected at the start of a conversation (`generate_reply timed out`).
- **A disconnect left the worker busy** for 25 to 30 seconds (the job never ended, then was killed), so the next conversation could find the
  worker "at full capacity". The job now ends when the session closes.

### Known gaps

- Wake words on the phone have not been run on a real device yet (audio capture, the background launch, battery use).
- Managed configurations have not been tried against a real MDM.
