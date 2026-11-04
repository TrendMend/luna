# web

The browser UI (one button, a transcript). It is served by the worker's token service (`python -m luna.web`,
`LUNA_WEB_DIR` points here) and installs on Android as a home-screen app. It stores the device access key in
the browser; a link or QR code from `luna-web add-device` sets it. To serve livekit-client locally instead of
from a CDN, run `web/vendor.sh` once.
