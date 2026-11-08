# Managing Luna from an MDM (Android Enterprise)

Luna publishes a **managed configuration** schema (`app/src/main/res/xml/app_restrictions.xml`). An MDM that supports Android Enterprise
managed configurations (Microsoft Intune, VMware Workspace ONE, Google's Android Management API / Admin console, Jamf, Hexnode, ...) reads it
from the installed app and shows it as a form; the answers reach the app as app restrictions.

A value the admin sets **wins** over what the person chose in the app (and the Settings screen says that some settings are set by the
organization). A value the admin leaves alone stays the person's choice. The person's own choices are kept underneath, so they come back if
the admin removes a setting.

## The settings

| Key | Type in the console | What it does |
|---|---|---|
| `setup_link` | text | The link made by `luna-web add-device` (`https://host/#key=...`). Connects the app with no typing. |
| `server_url` | text | The token service address (`https://luna.example.com`). Beats the server in `setup_link`. |
| `api_key` | text | This device's key. Beats the key in `setup_link`. |
| `user_name` | text | What Luna calls the person. |
| `assistant_name` | text | What the person calls the assistant (shown instead of "Luna"). |
| `listen_for_calls` | choice: `user` / `true` / `false` | The standing connection that lets timers, reminders and the house ring the phone. |
| `listen_for_wake_words` | choice: `user` / `true` / `false` | Listen for wake words (always-on microphone, with a notification). |
| `wake_words` | text | Comma-separated ids: `ron, rulon, alexa, hey_jarvis, hey_mycroft, hey_rhasspy`. Unknown ids are ignored. |
| `wake_sensitivity` | choice: `user` / `-50` / `0` / `50` | Fewer false wakes / standard / easier to wake. (Any whole number from -100 to 100 works in a JSON policy.) |
| `greeting_cue` | choice: `user` / `true` / `false` | The recorded "Hi!" when Luna opens. |
| `popup_end_button` | choice: `user` / `true` / `false` | Off hides the end button in the popup over other apps; tapping outside it closes it. |

"Not set" is an empty text or the choice `user`. That matters because some MDMs send every default in the schema back to the device: if a
default meant "off", every device that was never configured would be locked to off.

For `wake_words`, an empty text means "the person decides", and an empty **list** (`[]`, in a JSON policy) means "no wake words".

## Examples

Google Android Management API (`policy.applications[]`):

```json
{
  "packageName": "dev.luna.assistant",
  "installType": "FORCE_INSTALLED",
  "managedConfiguration": {
    "setup_link": "https://luna.example.com/#key=DEVICE_KEY",
    "user_name": "Rulon",
    "listen_for_wake_words": "true",
    "wake_words": "ron,rulon",
    "popup_end_button": "false"
  },
  "permissionGrants": [
    { "permission": "android.permission.RECORD_AUDIO", "policy": "GRANT" },
    { "permission": "android.permission.POST_NOTIFICATIONS", "policy": "GRANT" }
  ]
}
```

Intune and Workspace ONE take the same keys and values in their own "configuration designer" or JSON editor.

## Things to know

- **Permissions.** A managed configuration cannot grant the microphone permission. Without it, wake words ask the person once; with
  `RECORD_AUDIO` granted in the policy (above) there is no prompt. Showing Luna over other apps needs "display over other apps" and being the
  phone's selected assistant, which the person chooses in Android's settings.
- **When it applies.** Luna reads the policy when the app comes to the front and when Android announces a change while it is open. A policy
  that changes while the app is closed is picked up the next time it is opened (a standing call or wake word service keeps running with the
  settings it was started with until then).
- **The settings screen** shows a note when anything is managed, but does not yet grey out each managed control.
- **Secrets.** `api_key` and `setup_link` carry the device key in clear text in the policy, like any managed configuration value. Use one key
  per device so a single device can be revoked.
- **Not yet tested against a real MDM.** The parsing is unit tested (`ManagedConfigTest`); try it on one device with Google's *Test DPC* app
  (or your MDM's test tenant) before a wide roll-out.
