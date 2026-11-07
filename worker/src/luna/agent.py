import asyncio
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
from .opening import plan_opening
from .providers import build, preload
from .tools import WIDGET_RULES, WIDGET_TOOLS, Luna
from .ui import ATTR_UI, TOPIC, TOPIC_ACTION, Widgets, describe_action, parse_catalogs

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
SESS = CFG.get("session", {})
# The worker's built-in health/metrics server. 8081 is LiveKit's default but other things use it (the NUC's Thread border router does),
# so it can be moved with $LUNA_AGENT_PORT or `server.health_port`.
server = AgentServer(
    port=int(os.environ.get("LUNA_AGENT_PORT") or SRV.get("health_port", 8081)),
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


async def setup_widgets(ctx, session, luna: Luna, device: str | None, catalogs: list[str]):
    """A device that listed a catalog we know gets the widget tools; every other device never sees them."""
    if not (device and catalogs):
        await luna.update_tools([t for t in luna.tools if t.id not in WIDGET_TOOLS])
        return
    room = ctx.room

    async def send(text: str, topic: str):
        await room.local_participant.send_text(text, topic=topic, destination_identities=[device])

    async def on_action(action: dict):
        luna.audit.write("widget_action", surface=action["surface"], name=action["name"])
        session.generate_reply(user_input=describe_action(action))

    luna.widgets = Widgets(send, on_action)
    await luna.update_instructions(luna.instructions + "\n" + WIDGET_RULES)

    pending: set[asyncio.Task] = set()      # keep references so the tasks are not garbage collected

    def on_stream(reader, identity):
        if identity != device:          # only the device that started the conversation may press things
            return

        async def read():
            await luna.widgets.receive(await reader.read_all())
        task = asyncio.create_task(read())
        pending.add(task)
        task.add_done_callback(pending.discard)

    room.register_text_stream_handler(TOPIC_ACTION, on_stream)


# Explicit dispatch: the agent only joins rooms whose token asks for "luna" (see luna.web).
@server.rtc_session(agent_name=CFG["agent"]["name"])
async def entrypoint(ctx: agents.JobContext):
    session = AgentSession(
        stt=build("STT", CFG["stt"]),
        llm=build("LLM", CFG["llm"]),
        tts=build("TTS", CFG["tts"]),
        vad=ctx.proc.userdata["vad"],
        turn_handling=turn_handling_options(CFG["turns"]),
        user_away_timeout=SESS.get("idle_seconds", 30),   # nobody talking for this long: say goodbye and hang up
    )
    ha_cfg = CFG["home_assistant"]
    ha = HA(os.environ.get(ha_cfg["url_env"], "http://127.0.0.1:8123"), os.environ.get(ha_cfg["token_env"], ""),
            dry_run=os.environ.get("LUNA_DRY_RUN") == "1")
    ctx.add_shutdown_callback(ha.close)
    luna = Luna(CFG, ha, Audit())
    if not luna.search:    # no search configured (or the LLM's own tool is used): do not offer a tool that can only fail
        await luna.update_tools([t for t in luna.tools if getattr(t, "id", "") != "web_search"])
    for problem in await check_config(ha, CFG):  # log, but never refuse to talk because of them
        log.error("config check: %s", problem)
    await session.start(room=ctx.room, agent=luna)
    player = thinking_player()
    if player:  # started after the session, as the docs say; closed with the job
        await player.start(room=ctx.room, agent_session=session)
        ctx.add_shutdown_callback(player.aclose)

    hung_up = False
    ending = False             # the person said goodbye: hang up when the model has said its own
    spoke_after_end = False
    background: set[asyncio.Task] = set()

    def spawn(coro):
        task = asyncio.create_task(coro)
        background.add(task)                          # keep a reference so it is not garbage collected
        task.add_done_callback(background.discard)

    async def hang_up(reason: str, say_goodbye: bool = True):
        """End the conversation. Luna always says goodbye first (the configured one) unless it has just said its own."""
        nonlocal hung_up
        if hung_up:
            return
        hung_up = True
        goodbye = SESS.get("goodbye")
        if say_goodbye and goodbye:
            await session.say(goodbye)                # waits until it has been spoken
        ctx.shutdown(reason=reason)

    def request_end():
        """Called by the end_conversation tool. The model's reply to the tool result is the goodbye; hang up when it is done.
        If it says nothing, the configured goodbye does it."""
        nonlocal ending
        ending = True

        async def fallback():
            await asyncio.sleep(8)
            if not spoke_after_end:
                await hang_up("goodbye")
        spawn(fallback())

    luna.on_end = request_end

    @session.on("agent_state_changed")
    def _on_agent_state(ev):
        nonlocal spoke_after_end
        if not ending:
            return
        if ev.new_state == "speaking":
            spoke_after_end = True
        elif spoke_after_end and ev.old_state == "speaking" and ev.new_state in ("listening", "idle"):
            spawn(hang_up("goodbye", say_goodbye=False))     # it has said its goodbye

    @session.on("user_state_changed")
    def _on_user_state(ev):
        if ev.new_state == "away":
            spawn(hang_up("idle"))

    async def time_limit():
        await asyncio.sleep(SESS.get("max_seconds", 600))
        await hang_up("max_seconds")
    spawn(time_limit())

    # How this conversation started (wake word, a call from Home Assistant, a button) arrives as participant
    # attributes over LiveKit; the server set luna.device, the device set the rest.
    try:
        participant = await asyncio.wait_for(ctx.wait_for_participant(), timeout=5)
        attrs = dict(participant.attributes)
    except (asyncio.TimeoutError, TimeoutError):
        attrs = {}
    opening = plan_opening(CFG, attrs)
    await setup_widgets(ctx, session, luna, participant.identity if attrs else None, parse_catalogs(attrs.get(ATTR_UI)))
    luna.audit.write("conversation_start", device=attrs.get("luna.device"), wake_word=opening.wake_word,
                     said=bool(opening.say))
    if opening.extra:
        await luna.update_instructions(luna.instructions + "\n\n" + opening.extra)
    if opening.say:
        await session.say(opening.say)
    else:
        await session.generate_reply(instructions=opening.reply)


if __name__ == "__main__":
    agents.cli.run_app(server)
