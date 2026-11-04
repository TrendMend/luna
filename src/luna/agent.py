import logging
import os
import pathlib

from dotenv import load_dotenv
from livekit import agents
from livekit.agents import (AgentServer, AgentSession, AudioConfig, BackgroundAudioPlayer,
                            BuiltinAudioClip, TurnHandlingOptions, inference)
from livekit.plugins import silero

from .config import load_config
from .ha import HA, Audit, HAError
from .providers import build
from .tools import Luna

load_dotenv()
log = logging.getLogger("luna")
CFG = load_config(os.environ.get("LUNA_CONFIG", "config.yaml"))


async def check_config(ha: HA, cfg: dict) -> list[str]:
    """Compare config.yaml against the live Home Assistant: missing entities, notify services, tokens."""
    problems = []
    try:
        services = await ha.services()
    except HAError as e:
        return [f"cannot reach Home Assistant ({e}); tools will report failures"]
    ents = [d["entity"] for d in (cfg.get("devices") or {}).values()]
    ents += list((cfg.get("fan") or {}).values()) + list((cfg.get("sensors") or {}).values())
    ents += list((cfg.get("scripts") or {}).values()) + [d["entity"] for d in (cfg.get("doors") or {}).values()]
    if (cfg.get("harmony") or {}).get("remote"):
        ents.append(cfg["harmony"]["remote"])
    if (cfg.get("live_tv") or {}).get("script"):
        ents.append(cfg["live_tv"]["script"])
    for e in ents:
        if await ha.state(e) is None:
            problems.append(f"entity {e} does not exist in Home Assistant")
    for s in (cfg.get("caregivers") or {}).get("notify", []):
        if s not in services:
            problems.append(f"notify service {s} does not exist")
    return problems

server = AgentServer()


def thinking_player():
    cfg = CFG.get("thinking_sound")
    if not cfg:
        return None
    src = cfg["file"]
    if src.startswith("builtin:"):
        src = BuiltinAudioClip[src.split(":", 1)[1]]
    else:
        src = str(pathlib.Path(src).resolve())
    return BackgroundAudioPlayer(thinking_sound=AudioConfig(
        src, volume=cfg.get("volume", 0.4), fade_in=cfg.get("fade_in", 0.0), fade_out=cfg.get("fade_out", 0.0)))


def prewarm(proc: agents.JobProcess):
    proc.userdata["vad"] = silero.VAD.load()


server.setup_fnc = prewarm


@server.rtc_session(agent_name=CFG["agent"]["name"])
async def entrypoint(ctx: agents.JobContext):
    t = CFG["turns"]
    session = AgentSession(
        stt=build("STT", CFG["stt"]),
        llm=build("LLM", CFG["llm"]),
        tts=build("TTS", CFG["tts"]),
        vad=ctx.proc.userdata["vad"],
        turn_handling=TurnHandlingOptions(
            turn_detection=inference.TurnDetector(version="v1-mini"),  # local model; "v1" calls a cloud gateway
            endpointing={"min_delay": t["min_endpointing_delay"], "max_delay": t["max_endpointing_delay"]},
            interruption={"enabled": t["allow_interruptions"]},
        ),
    )
    ha_cfg = CFG["home_assistant"]
    ha = HA(os.environ.get(ha_cfg["url_env"], "http://127.0.0.1:8123"), os.environ.get(ha_cfg["token_env"], ""),
            dry_run=os.environ.get("LUNA_DRY_RUN") == "1")
    luna = Luna(CFG, ha, Audit())
    for problem in await check_config(ha, CFG):  # log, but never refuse to talk because of them
        log.error("config check: %s", problem)
    await session.start(room=ctx.room, agent=luna)
    player = thinking_player()
    if player:
        await player.start(room=ctx.room, agent_session=session)
    await session.generate_reply(instructions="Greet the user briefly and ask how you can help.")


if __name__ == "__main__":
    agents.cli.run_app(server)
