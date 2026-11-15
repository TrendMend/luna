# TODO

Ordered roughly by what blocks what.

## First real runs
Done: the maintainer reports (2026-11-13) that everything below has been confirmed working in production use. There are no logs or
test records in the repository for it.
- [x] The full system on a home network: LiveKit server, token service, worker, and a phone or tablet with its own access key.
- [x] The Android app on a device.
- [x] Real Home Assistant actions with `LUNA_DRY_RUN=0`.
- [x] A web-search provider.

## UI (recommended after the UI review)
- [ ] **Android design pass.** A theme exists (`ui/theme/`: colour, type, shape) and the screens have previews. Still to check or do:
  dynamic colour, edge-to-edge, large-text defaults and system font scale, touch targets of at least 56dp, content descriptions, and
  Settings as a proper screen rather than a dialog. None of it has been seen on a device.
- [x] Compose previews and screenshot tests exist (`android/app/src/screenshotTest/`); keep reviewing the renders when the UI changes.
- [x] Web page restyled in Material 3 with the Android app's colour roles (`web/a2ui.css`); check it on a real phone-sized viewport.
- [ ] Decide whether the widgets look right: the Android M3 catalog and the web renderer draw the same messages differently.

## Android: first run
- [ ] Starts, asks for the microphone, connects to a room, plays the agent's audio, shows the transcript.
- [ ] Worker's widget JSON actually parses with the Android A2UI parser and draws (only the web renderer has seen it).
- [ ] Control-room service stays up (battery restrictions may kill it); full-screen call notification opens over the lock screen.
- [ ] Restart the wake-word and control services after a reboot. `BootReceiver.kt` only re-applies an MDM's launcher-icon policy: Android 14+ does not allow starting a microphone service from boot, so the app must be opened once after a restart (limit documented in `android/README.md`).
- [ ] Keep the access key in the Android Keystore instead of plain private storage.
- [ ] `https://` (Tailscale or a certificate) before leaving the home LAN; `usesCleartextTraffic` is on today.
- [x] On-device wake words are written and tested against the Python original; reported working in production.

## Worker
- [ ] Run timers/reminders against a real LiveKit server: `luna.call` delivery into the control room is only unit-tested with a fake.
- [ ] `web_search` `native` provider needs `llm.plugin: openai.responses`; untested. Pick a search provider and set its key.
- [ ] Run the NixOS module (`LUNA_WEB_URL`) on a real machine.
- [ ] Compose and Kubernetes changes (shared `luna-data` volume, `LUNA_WEB_URL`) were validated as YAML only.

## Gemini-like capabilities still missing
- [ ] Calendar and agenda, notes/lists, general "remind me every day" (repeating reminders).
- [ ] Referrals beyond what search finds: a curated list of local numbers and services in `config.yaml`.
- [ ] An emergency flow beyond "tell a caregiver" (who is called, in what order).

## Protocol
- [ ] A2UI `v1.0` is the current spec; Luna is pinned to v0.9.1 because the Android renderer is. Revisit when `androidx.a2ui` moves.
- [ ] Web renderer: List templates, function calls inside values, Video and AudioPlayer.
