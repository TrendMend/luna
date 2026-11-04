"""The assistant's tools. Each one validates against the config allowlist, calls Home Assistant,
reads the result back where possible, and reports honestly to the model.

Conventions (from the LiveKit Agents docs on function tools):
- failures raise ToolError, so the model is told and can explain it to the person;
- anything that changes the world calls context.disallow_interruptions() first, so the person
  speaking can't cut a tool off half way and leave the model not knowing what happened;
- tools that take more than a few seconds acknowledge at once with context.update() and keep the
  person company with context.with_filler() while they work."""
import asyncio
import logging
import os

from livekit.agents import Agent, RunContext, function_tool
from livekit.agents.llm import ToolError

from .ha import HA, Audit, Caregivers, Devices, HAError
from .info import Doors, Weather
from .tv import Favorites, Harmony, LiveTV, norm

log = logging.getLogger("luna.tools")

RULES = """
Tools and honesty rules:
- You can only control the things listed below. Never claim to have done something unless a tool returned success.
- If a tool reports a failure, tell the person plainly and offer to tell a caregiver.
- If the person asks you to call or tell a caregiver, or sounds distressed, scared, in pain, or hurt, use
  notify_caregivers (urgent=true for pain, falls, fear, or anything that sounds like an emergency) and tell them you did.
- Never give medical advice. For anything health-related, offer to notify a caregiver.
- Cable TV channels and Live TV (the Jellyfin player) channels are different things. If it is unclear which one the person
  means, ask. Live TV channels are looked up by name, e.g. "Super Cool Station".
- Changing channels or starting a TV activity takes a while. Say what you are doing in one short sentence, then wait.
"""

SLOW_FILLER = dict(delay=8, interval=12, max_steps=3)


class Luna(Agent):
    def __init__(self, cfg: dict, ha: HA, audit: Audit):
        self.cfg, self.ha, self.audit = cfg, ha, audit
        self.devices = Devices(cfg.get("devices"))
        self.fan = cfg.get("fan") or {}
        self.sensors = {norm(k): (k, v) for k, v in (cfg.get("sensors") or {}).items()}
        self.scripts = {norm(k): v for k, v in (cfg.get("scripts") or {}).items()}
        self.caregivers = Caregivers(ha, cfg.get("caregivers"), audit, cfg.get("person", ""))
        self.harmony = Harmony(ha, cfg["harmony"]) if cfg.get("harmony") else None
        self.favorites = Favorites((cfg.get("harmony") or {}).get("cable", {}).get("favorites"))
        jf = cfg.get("live_tv")
        self.live = LiveTV(ha, jf) if jf else None
        self.doors = Doors(ha, cfg.get("doors")) if cfg.get("doors") else None
        self.weather = Weather(cfg["weather"]) if cfg.get("weather") else None

        lines = [cfg["agent"]["instructions"].strip(), RULES.strip(),
                 "Devices you can control: " + ", ".join(self.devices.names()) + "."]
        if self.fan:
            lines.append("Bedroom fan commands: " + ", ".join(self.fan) + ".")
        if self.sensors:
            lines.append("Readings you can report: " + ", ".join(v[0] for v in self.sensors.values()) + ".")
        if self.harmony:
            lines.append("TV activities: " + ", ".join(self.harmony.activity_names()) + ".")
            lines.append("TV buttons: " + ", ".join(sorted(self.harmony.commands)) + ".")
        if self.scripts:
            lines.append("Other things you can show on the TV: " + ", ".join(self.scripts) + ".")
        if self.doors:
            lines.append("Doors you can check (you can only look, never lock or unlock): " + ", ".join(self.doors.names()) + ".")
        super().__init__(instructions="\n\n".join(lines))

    # ---- helpers -----------------------------------------------------------------------------
    def _fail(self, kind: str, err: Exception, **fields) -> ToolError:
        """Log a failure and return the ToolError to raise (the model relays its message)."""
        self.audit.write(kind + "_failed", error=str(err), **fields)
        return ToolError(f"That did not work: {err}")

    # ---- lights ------------------------------------------------------------------------------
    @function_tool
    async def control_device(self, context: RunContext, device: str, action: str, value: float | None = None) -> str:
        """Control a light or other device in the home, such as the bedroom light or the corner light.

        Args:
            device: Name of the device, e.g. "bedroom light" or "corner light".
            action: One of on, off, brightness (only those allowed for that device).
            value: Number for brightness (1-100).
        """
        context.disallow_interruptions()
        try:
            d, action, (domain, service, data) = self.devices.plan(device, action, value)
            await self.ha.call(domain, service, data)
            result = f"Done: {d['name']} {action}."
            expected = {"on": "on", "off": "off"}.get(action)
            if expected and not self.ha.dry_run:
                await asyncio.sleep(1.0)
                st = await self.ha.state(d["entity"])
                if st and st["state"] != expected:
                    result = f"I sent the command but {d['name']} still reports '{st['state']}'. Tell the person it may not have worked."
            self.audit.write("control", device=d["name"], action=action, value=value, result=result)
            return result
        except HAError as e:
            raise self._fail("control", e, device=device, action=action, value=value)

    @function_tool
    async def control_fan(self, context: RunContext, command: str) -> str:
        """Control the bedroom fan. Commands: off, speed (turns it on, then steps through the speeds),
        mode (sleep or whisper), timer, oscillate.

        Args:
            command: One of the fan commands.
        """
        context.disallow_interruptions()
        ent = {norm(k): v for k, v in self.fan.items()}.get(norm(command))
        if not ent:
            raise ToolError(f"I can't do '{command}' on the fan. I can: {', '.join(self.fan)}.")
        try:
            await self.ha.call("button", "press", {"entity_id": ent})
            self.audit.write("fan", command=command)
            return f"Pressed {command} on the fan."
        except HAError as e:
            raise self._fail("fan", e, command=command)

    # ---- read-only information ---------------------------------------------------------------
    @function_tool
    async def get_readings(self, context: RunContext, name: str = "") -> str:
        """Report house temperatures and the bedroom air quality. Leave name empty for all of them,
        or give one, e.g. "kitchen temperature" or "bedroom temperature".

        Args:
            name: Which reading, or empty for everything.
        """
        key = norm(name)
        picks = [v for k, v in self.sensors.items() if not key or key in k or k in key]
        if not picks:
            raise ToolError(f"I don't have a reading called '{name}'. I have: {', '.join(v[0] for v in self.sensors.values())}.")
        out = []
        for label, ent in picks:
            try:
                st = await self.ha.state(ent)
            except HAError as e:
                out.append(f"{label}: could not read ({e})")
                continue
            if not st or st["state"] in ("unavailable", "unknown"):
                out.append(f"{label}: not available right now")
            else:
                out.append(f"{label}: {st['state']} {st['attributes'].get('unit_of_measurement', '')}".strip())
        return "; ".join(out)

    @function_tool
    async def check_doors(self, context: RunContext, door: str = "") -> str:
        """Check whether the doors are open or closed, and whether the front door is locked. Leave door empty
        to check all of them. You can only report status; you cannot lock, unlock or open anything.

        Args:
            door: Which door, e.g. "front door", or empty for all.
        """
        if not self.doors:
            raise ToolError("Door checking is not set up.")
        try:
            result = await self.doors.report(door)
            self.audit.write("check_doors", door=door, result=result)
            return result
        except HAError as e:
            raise self._fail("check_doors", e, door=door)

    @function_tool
    async def get_weather(self, context: RunContext, when: str = "now") -> str:
        """Get the local weather: now, today, tomorrow, or the day after tomorrow.

        Args:
            when: now, today, tomorrow, or day after tomorrow.
        """
        if not self.weather:
            raise ToolError("Weather is not set up.")
        try:
            return await self.weather.report(when)
        except HAError as e:
            raise self._fail("weather", e, when=when)

    # ---- TV ----------------------------------------------------------------------------------
    @function_tool(on_duplicate="confirm")
    async def tv_activity(self, context: RunContext, activity: str) -> str:
        """Start a TV activity: cable, youtube, movies, apple tv, dashboard (shows the home dashboard on the TV),
        or off (turns the TV off). Starting one takes 15 to 20 seconds.

        Args:
            activity: Which activity.
        """
        context.disallow_interruptions()
        if not self.harmony:
            raise ToolError("TV control is not set up.")
        try:
            await context.update(f"Starting the TV activity '{activity}'. This takes about 20 seconds.")
            async with context.with_filler("Still working on the TV.", **SLOW_FILLER):
                result = await self.harmony.activity(activity)
            self.audit.write("tv_activity", activity=activity, result=result)
            return result
        except HAError as e:
            raise self._fail("tv", e, activity=activity)

    @function_tool
    async def tv_button(self, context: RunContext, button: str) -> str:
        """Press a TV or cable remote button: volume up, volume down, mute, channel up, channel down,
        previous channel, guide, pause, play.

        Args:
            button: Which button.
        """
        context.disallow_interruptions()
        if not self.harmony:
            raise ToolError("TV control is not set up.")
        try:
            result = await self.harmony.command(button)
            self.audit.write("tv_button", button=button)
            return result
        except HAError as e:
            raise self._fail("tv", e, button=button)

    @function_tool(on_duplicate="confirm")
    async def cable_channel(self, context: RunContext, channel: str) -> str:
        """Change the CABLE TV (not Jellyfin) to a channel, by name or number, e.g. "the weather channel" or "205".
        It starts the cable activity first if needed, which takes 15 to 20 seconds.

        Args:
            channel: A remembered channel name, or a channel number.
        """
        context.disallow_interruptions()
        if not self.harmony:
            raise ToolError("TV control is not set up.")
        number, candidates = self.favorites.lookup(channel)
        if not number:
            if candidates:
                raise ToolError(f"Which one do you mean: {', '.join(candidates)}? Ask the person.")
            known = ", ".join(sorted(self.favorites.all())) or "none yet"
            raise ToolError(f"I don't know a cable channel called '{channel}'. Known channels: {known}. "
                            "Ask for the number, and I can remember it.")
        try:
            await context.update(f"Changing the cable TV to channel {number}. This takes about 20 seconds.")
            async with context.with_filler("Still changing the channel.", **SLOW_FILLER):
                result = await self.harmony.cable_channel(number)
            self.audit.write("cable_channel", channel=channel, number=number)
            return result
        except HAError as e:
            raise self._fail("cable", e, channel=channel, number=number)

    @function_tool
    async def remember_cable_channel(self, context: RunContext, name: str, number: str) -> str:
        """Remember a favourite cable channel so it can be asked for by name later.

        Args:
            name: What the person calls it, e.g. "hockey" or "the news".
            number: The cable channel number, digits only.
        """
        context.disallow_interruptions()
        try:
            self.favorites.remember(name, number.strip())
            self.audit.write("remember_channel", name=name, number=number)
            return f"Okay, I will remember that {name} is channel {number}."
        except HAError as e:
            raise self._fail("remember", e, name=name, number=number)

    @function_tool(on_duplicate="confirm")
    async def live_tv_channel(self, context: RunContext, channel: str) -> str:
        """Play a Live TV channel (the Jellyfin player) by name or number, e.g. "Super Cool Station".
        This switches the TV to the Jellyfin player and takes about half a minute.

        Args:
            channel: The channel name (as spoken) or number.
        """
        context.disallow_interruptions()
        if not self.live:
            raise ToolError("Live TV is not set up.")
        try:
            number, candidates = self.live.resolve(channel)
            if not number:
                if candidates:
                    raise ToolError(f"I'm not sure which channel you mean. Did you mean: {', '.join(candidates)}?")
                raise ToolError(f"I couldn't find a Live TV channel called '{channel}'.")
            await context.update(f"Starting Live TV channel {number}. This takes about half a minute.")
            async with context.with_filler("Still starting the channel.", **SLOW_FILLER):
                result = await self.live.play(number)
            self.audit.write("live_tv", channel=channel, number=number)
            return result
        except HAError as e:
            raise self._fail("live_tv", e, channel=channel)

    @function_tool
    async def show_on_tv(self, context: RunContext, item: str) -> str:
        """Show something on the TV from the list of extra items, such as the photo gallery.

        Args:
            item: Which item.
        """
        context.disallow_interruptions()
        ent = self.scripts.get(norm(item))
        if not ent:
            raise ToolError(f"I can't show '{item}'. I can show: {', '.join(self.scripts) or 'nothing'}.")
        try:
            await self.ha.call("script", "turn_on", {"entity_id": ent})
            self.audit.write("show_on_tv", item=item)
            return f"Showing {item} on the TV."
        except HAError as e:
            raise self._fail("show_on_tv", e, item=item)

    # ---- caregivers --------------------------------------------------------------------------
    @function_tool
    async def notify_caregivers(self, context: RunContext, message: str, urgent: bool = False) -> str:
        """Send a notification to the caregivers' phones.

        Args:
            message: What to tell them, in plain words, including what the person said or needs.
            urgent: True for pain, a fall, fear, or anything that could be an emergency. These alert loudly.
        """
        context.disallow_interruptions()  # never let speech cut this off: the model must learn whether it went out
        result = await self.caregivers.notify(message, urgent)
        if result.startswith("I could NOT reach"):
            raise ToolError(result)
        return result
