#!/bin/sh
# Optional: serve livekit-client from this server instead of the jsDelivr CDN (needed for offline use).
# Run once from the repo root. It downloads one file (MIT/Apache licensed) into web/vendor/.
set -eu
VER="${LIVEKIT_CLIENT_VERSION:-2}"
mkdir -p web/vendor
curl -fsSL "https://cdn.jsdelivr.net/npm/livekit-client@${VER}/dist/livekit-client.umd.min.js" -o web/vendor/livekit-client.umd.min.js
echo "wrote web/vendor/livekit-client.umd.min.js ($(wc -c < web/vendor/livekit-client.umd.min.js) bytes)"
