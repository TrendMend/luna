# Managed (locked) build

A second build flavor, `managed`, for institutions, kiosks and shared devices. It is the same
app, but changes can only be made by whoever knows the **admin password**, which the person
installing it chooses during setup.

It installs next to the standard app (`com.example.hawakeword.managed`, label "HA Wake Word Managed")
and uses its own sign-in redirect (`hawakeword-managed://auth`).

## What it does

| | Standard | Managed |
|---|---|---|
| Admin password | none | set in onboarding (min. 6 characters), required afterwards |
| Settings, wake word switches/gears, add/remove models | open | behind the password |
| Stop listening (app and notification) | open | password in the app; no Stop button in the notification |
| Starting to listen | button | automatic when the app is opened (if signed in and mic allowed) |
| Start on boot | option | always attempted |
| Re-lock | n/a | after about 2 minutes without touching the screen, or "Lock now" |

Home shows what is happening (listening, last word, live scores) to anyone, read-only.
Rows for wake words are greyed out until the app is unlocked.

Password storage: PBKDF2-HMAC-SHA256, random salt, 120,000 iterations, kept in the app's
encrypted preferences. Wrong guesses are throttled: five free attempts, then a lockout that
doubles from 30 s up to 15 min.

## Building

```bash
./gradlew assembleManagedRelease -PhaUrl=http://homeassistant.local:8123
./gradlew assembleManagedDebug
```

`-PhaUrl=` pre-fills the Home Assistant address so installers only have to sign in.
Sign release builds with your own keystore (standard Android signing config), and host
`docs/index.html` (it already lists both redirect schemes) as the OAuth client page.

Output: `app/build/outputs/apk/managed/...`.

## Important limits

- **There is no password recovery.** Clearing the app's data or reinstalling removes the password
  and the Home Assistant registration. Treat the password like any other admin credential.
- The password is an app-level lock, not a security boundary on its own. Anyone with the
  phone's settings can still force-stop, clear data or uninstall the app. On managed hardware:
  - Use **Android Enterprise** device-owner / kiosk (lock task) mode and allow-list this app.
  - Block uninstall, "clear data" and force-stop with your MDM, and grant the microphone and
    battery exemption centrally.
  - Pre-install the APK and provision it by MDM instead of by hand where possible.
- Anyone who can read the device storage as root is outside what this protects against.
