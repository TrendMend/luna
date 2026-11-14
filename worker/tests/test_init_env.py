"""scripts/init_env.py: a fresh .env with generated secrets and nothing else changed."""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "init_env.py"


def run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)


def values(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def test_generates_secrets_and_keeps_the_rest(tmp_path):
    env = tmp_path / ".env"
    r = run("--env", str(env), "--device", "kitchen")
    assert r.returncode == 0, r.stderr
    got, template = values(env), values(ROOT / ".env.example")
    assert got["LIVEKIT_API_SECRET"] != template["LIVEKIT_API_SECRET"] and len(got["LIVEKIT_API_SECRET"]) == 64
    name, _, key = got["LUNA_ACCESS_KEYS"].partition(":")
    assert name == "kitchen" and len(key) >= 16
    assert f"#key={key}" in r.stdout                                  # the setup link
    assert got["LUNA_DRY_RUN"] == "1" and got["HA_TOKEN"] == ""       # safe default, nothing invented for the user
    assert {k: v for k, v in got.items() if k not in ("LIVEKIT_API_SECRET", "LUNA_ACCESS_KEYS")} == \
           {k: v for k, v in template.items() if k not in ("LIVEKIT_API_SECRET", "LUNA_ACCESS_KEYS")}


def test_never_overwrites_and_rejects_bad_names(tmp_path):
    env = tmp_path / ".env"
    env.write_text("KEEP=me\n")
    assert run("--env", str(env)).returncode == 1 and env.read_text() == "KEEP=me\n"
    assert run("--env", str(tmp_path / "other"), "--device", "bad name!").returncode == 2
