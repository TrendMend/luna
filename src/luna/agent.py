import logging
import pathlib

import yaml
from dotenv import load_dotenv
from livekit import agents
from livekit.agents import Agent, AgentServer, AgentSession, TurnHandlingOptions, inference
from livekit.plugins import silero

from .providers import build

load_dotenv()
log = logging.getLogger("luna")
CFG = yaml.safe_load(pathlib.Path(__import__("os").environ.get("LUNA_CONFIG", "config.yaml")).read_text())

server = AgentServer()


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
    await session.start(room=ctx.room, agent=Agent(instructions=CFG["agent"]["instructions"]))
    await session.generate_reply(instructions="Greet the user briefly and ask how you can help.")


if __name__ == "__main__":
    agents.cli.run_app(server)
