"""The assistant's tools. Each one validates against the config allowlist, calls Home Assistant,
reads the result back where possible, and returns an honest message for the model to relay."""
import asyncio
import logging

from livekit.agents import Agent, RunContext, function_tool

from .ha import HA, Audit, Caregivers, Devices, HAError

log = logging.getLogger("luna.tools")

RULES = """
Tools and honesty rules:
- You can only control the devices listed below. Never claim to have done something unless a tool returned success.
- If a tool reports a failure, tell the person plainly and offer to tell a caregiver.
- If the person asks you to call or tell a caregiver, or sounds distressed, scared, in pain, or hurt, use
  notify_caregivers (urgent=true for pain, falls, fear, or anything that sounds like an emergency) and tell them you did.
- Never give medical advice. For anything health-related, offer to notify a caregiver.
"""


class Luna(Agent):
    def __init__(self, cfg: dict, ha: HA, audit: Audit):
        self.cfg, self.ha, self.audit = cfg, ha, audit
        self.devices = Devices(cfg.get("devices"))
        self.tv = cfg.get("tv") or {}
        self.caregivers = Caregivers(ha, cfg.get("caregivers"), audit, cfg.get("person", ""))
        lines = [cfg["agent"]["instructions"].strip(), RULES.strip(), "Devices you can control: " + ", ".join(self.devices.names()) + "."]
        if self.tv.get("commands"):
            lines.append("TV commands: " + ", ".join(self.tv["commands"]) + ".")
        if self.tv.get("channels"):
            lines.append("TV channels you can switch to: " + ", ".join(self.tv["channels"]) + ".")
        super().__init__(instructions="\n\n".join(lines))

    @function_tool
    async def control_device(self, context: RunContext, device: str, action: str, value: float | None = None) -> str:
        """Control a device in the home, such as the bedroom light or the air conditioner.

        Args:
            device: Name of the device, e.g. "bedroom light" or "air conditioner".
            action: One of on, off, brightness, set_temperature, volume, mute, unmute (only those allowed for the device).
            value: Number for brightness (1-100), set_temperature (degrees C) or volume (0-100).
        """
        try:
            d, action, (domain, service, data) = self.devices.plan(device, action, value)
            await self.ha.call(domain, service, data)
            result = f"Done: {d['name']} {action}."
            expected = {"on": "on", "off": "off"}.get(action)
            if expected:
                await asyncio.sleep(1.0)
                st = await self.ha.state(d["entity"])
                if st and st["state"] != expected:
                    result = f"I sent the command but {d['name']} still reports '{st['state']}'. Tell the person it may not have worked."
            self.audit.write("control", device=d["name"], action=action, value=value, result=result)
            return result
        except HAError as e:
            self.audit.write("control_failed", device=device, action=action, value=value, error=str(e))
            return f"That did not work: {e}"

    @function_tool
    async def tv_command(self, context: RunContext, command: str) -> str:
        """Press a button on the TV remote, e.g. power, mute, volume up, volume down, channel up, channel down.

        Args:
            command: The button to press, from the list of TV commands.
        """
        cmds = {k.lower(): v for k, v in (self.tv.get("commands") or {}).items()}
        raw = cmds.get(command.strip().lower())
        if not raw or not self.tv.get("remote"):
            return f"I can't do '{command}' on the TV. I can: {', '.join(cmds) or 'nothing configured'}."
        try:
            await self.ha.call("remote", "send_command", {"entity_id": self.tv["remote"], "command": raw})
            self.audit.write("tv_command", command=command)
            return f"Pressed {command} on the TV."
        except HAError as e:
            self.audit.write("tv_failed", command=command, error=str(e))
            return f"That did not work: {e}"

    @function_tool
    async def tv_channel(self, context: RunContext, channel: str) -> str:
        """Switch the TV to a named channel or source.

        Args:
            channel: Name of the channel, from the list of TV channels.
        """
        chans = {k.lower(): v for k, v in (self.tv.get("channels") or {}).items()}
        spec = chans.get(channel.strip().lower())
        if not spec:
            return f"I don't know a channel called '{channel}'. I know: {', '.join(chans) or 'none configured'}."
        try:
            for call in spec.get("calls", []):
                dom, svc = call["service"].split(".", 1)
                await self.ha.call(dom, svc, call.get("data", {}))
            for digit in str(spec.get("digits", "")):
                await self.ha.call("remote", "send_command", {"entity_id": self.tv["remote"], "command": digit})
                await asyncio.sleep(0.4)
            self.audit.write("tv_channel", channel=channel)
            return f"Switched the TV to {channel}."
        except HAError as e:
            self.audit.write("tv_failed", channel=channel, error=str(e))
            return f"That did not work: {e}"

    @function_tool
    async def notify_caregivers(self, context: RunContext, message: str, urgent: bool = False) -> str:
        """Send a notification to the caregivers' phones.

        Args:
            message: What to tell them, in plain words, including what the person said or needs.
            urgent: True for pain, a fall, fear, or anything that could be an emergency. These alert loudly.
        """
        return await self.caregivers.notify(message, urgent)
