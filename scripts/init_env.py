#!/usr/bin/env python3
"""Create `.env` from `.env.example` with fresh random secrets, so a Docker deployment needs no manual editing of those.

    python scripts/init_env.py                 # writes .env (never overwrites an existing one)
    python scripts/init_env.py --device phone  # the name of the first device (default: laptop)

It generates the LiveKit API secret and one access key for the first device, and leaves everything that is yours to supply
(Home Assistant URL and token, provider API keys) empty. `LUNA_DRY_RUN` stays at 1: Luna reads Home Assistant but changes nothing
until you set it to 0. Nothing secret is printed except the one-time setup link for the browser.
"""
import argparse
import pathlib
import re
import secrets
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def set_value(text: str, name: str, value: str) -> str:
    """Replace the value of `NAME=...` (a line that starts with the name), keeping line endings and everything else."""
    pattern = re.compile(rf"^({re.escape(name)}=).*$", re.M)
    if not pattern.search(text):
        raise SystemExit(f".env.example has no {name}= line; cannot continue")
    return pattern.sub(lambda m: m.group(1) + value, text, count=1)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--device", default="laptop", help="name of the first device (letters, digits, dashes)")
    ap.add_argument("--env", default=str(ROOT / ".env"), help="where to write (default: .env at the repository root)")
    args = ap.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9-]{1,30}", args.device):
        print("The device name may only contain letters, digits and dashes.", file=sys.stderr)
        return 2
    target = pathlib.Path(args.env)
    if target.exists():
        print(f"{target} already exists; not touching it. Delete it first if you want a fresh one.", file=sys.stderr)
        return 1
    text = (ROOT / ".env.example").read_text(encoding="utf-8")
    access_key = secrets.token_urlsafe(24)           # 32 characters; the token service requires at least 16
    text = set_value(text, "LIVEKIT_API_SECRET", secrets.token_hex(32))
    text = set_value(text, "LUNA_ACCESS_KEYS", f"{args.device}:{access_key}")
    target.write_text(text, encoding="utf-8", newline="")
    print(f"Wrote {target}")
    print("Next: put your provider API keys in it (see worker/config.yaml for which ones), then:")
    print("    docker compose up --build")
    print(f"Open this link once in the browser on this computer (it saves the key for '{args.device}'):")
    print(f"    http://localhost:8080/#key={access_key}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
