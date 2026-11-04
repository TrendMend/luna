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
from .providers import build, preload
from .tools import Luna

load_dotenv()
log = logging.getLogger("luna")
CFG = load_config(os.environ.get("LUNA_CONFIG", "config.yaml"))
preload(CFG)   # plugins must be imported on the main thread


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

def turn_handling_options(turns: dict) -> TurnHandlingOptions:
    """Turn-taking from config.yaml. Everything is pinned local: the audio turn detector's v1-mini
    runs on the CPU, and interruptions use VAD (the defaults would call LiveKit's cloud in dev mode)."""
    td = {"version": "v1-mini"}
    if turns.get("unlikely_threshold") is not None:
        td["unlikely_threshold"] = float(turns["unlikely_threshold"])
    return TurnHandlingOptions(
        turn_detection=inference.TurnDetector(**td),
        endpointing={"mode": turns.get("endpointing_mode", "dynamic"),
                     "min_delay": turns["min_endpointing_delay"], "max_delay": turns["max_endpointing_delay"]},
        interruption={"enabled": turns["allow_interruptions"], "mode": "vad",
                      "min_duration": turns.get("min_interruption_duration", 0.5),
                      "min_words": turns.get("min_interruption_words", 1)},
    )


SRV = CFG.get("server", {})
server = AgentServer(
    num_idle_processes=SRV.get("idle_processes", 1),
    drain_timeout=SRV.get("drain_timeout", 600),
    load_threshold=0.99,  # the default (0.7 CPU) would silently stop accepting conversations on a busy box
)
# Load = running conversations / max_sessions, not CPU: a NUC that is also busy running Home Assistant
# must still answer.
server.load_fnc = lambda s: len(s.active_jobs) / max(1, SRV.get("max_sessions", 2))


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


# Explicit dispatch: the agent only joins rooms whose token asks for "luna" (see luna.web).
@server.rtc_session(agent_name=CFG["agent"]["name"])
async def entrypoint(ctx: agents.JobContext):
    session = AgentSession(
        stt=build("STT", CFG["stt"]),
        llm=build("LLM", CFG["llm"]),
        tts=build("TTS", CFG["tts"]),
        vad=ctx.proc.userdata["vad"],
        turn_handling=turn_handling_options(CFG["turns"]),
    )
    ha_cfg = CFG["home_assistant"]
    ha = HA(os.environ.get(ha_cfg["url_env"], "http://127.0.0.1:8123"), os.environ.get(ha_cfg["token_env"], ""),
            dry_run=os.environ.get("LUNA_DRY_RUN") == "1")
    ctx.add_shutdown_callback(ha.close)
    luna = Luna(CFG, ha, Audit())
    for problem in await check_config(ha, CFG):  # log, but never refuse to talk because of them
        log.error("config check: %s", problem)
    await session.start(room=ctx.room, agent=luna)
    player = thinking_player()
    if player:  # started after the session, as the docs say; closed with the job
        await player.start(room=ctx.room, agent_session=session)
        ctx.add_shutdown_callback(player.aclose)
    await session.generate_reply(instructions="Greet the user briefly and ask how you can help.")


if __name__ == "__main__":
    agents.cli.run_app(server)
