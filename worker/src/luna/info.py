"""Read-only information sources: door/lock status (from Home Assistant) and weather (direct API)."""
import logging

import aiohttp

from .ha import HA, HAError
from .tv import norm

log = logging.getLogger("luna.info")

# What each sensor kind reports for a binary_sensor state. HA device classes: door -> on = open;
# lock -> on = UNLOCKED. `invert: true` in config flips this for a sensor wired the other way.
WORDS = {"contact": {"on": "open", "off": "closed"}, "lock": {"on": "unlocked", "off": "locked"}}


class Doors:
    def __init__(self, ha: HA, cfg: dict | None):
        self.ha = ha
        self.items = {norm(k): (k, v) for k, v in (cfg or {}).items()}

    def names(self) -> list[str]:
        return [v[0] for v in self.items.values()]

    async def report(self, name: str = "") -> str:
        key = norm(name)
        if key in self.items:                      # an exact name wins ("front door lock" is not "front door")
            picks = [self.items[key]]
        else:
            picks = [v for k, v in self.items.items() if not key or key in k]
        if not picks:
            raise HAError(f"I don't have a door or lock called '{name}'. I have: {', '.join(self.names())}.")
        out = []
        for label, spec in picks:
            try:
                st = await self.ha.state(spec["entity"])
            except HAError:
                st = None
            state = (st or {}).get("state")
            if state not in ("on", "off"):
                out.append(f"{label}: I can't tell right now")  # never guess "closed" for an unknown sensor
                continue
            if spec.get("invert"):
                state = "off" if state == "on" else "on"
            out.append(f"{label}: {WORDS[spec.get('kind', 'contact')][state]}")
        return "; ".join(out)


WMO = {0: "clear sky", 1: "mostly clear", 2: "partly cloudy", 3: "overcast", 45: "foggy", 48: "foggy with frost",
       51: "light drizzle", 53: "drizzle", 55: "heavy drizzle", 56: "freezing drizzle", 57: "freezing drizzle",
       61: "light rain", 63: "rain", 65: "heavy rain", 66: "freezing rain", 67: "freezing rain",
       71: "light snow", 73: "snow", 75: "heavy snow", 77: "snow grains", 80: "light showers", 81: "showers",
       82: "heavy showers", 85: "snow showers", 86: "heavy snow showers", 95: "thunderstorms",
       96: "thunderstorms with hail", 99: "thunderstorms with heavy hail"}


class Weather:
    """Open-Meteo (no API key). Add providers by adding a method and a `provider:` value."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.base = cfg.get("base_url", "https://api.open-meteo.com").rstrip("/")
        self.place = cfg.get("name", "here")
        self.metric = cfg.get("units", "metric") == "metric"
        self.card: dict | None = None  # the last report, for the card on the person's screen

    async def _geocode(self, place: str) -> tuple[float, float, str]:
        """Open-Meteo's free geocoder (no key). Returns latitude, longitude and a spoken name."""
        url = self.cfg.get("geocoding_url", "https://geocoding-api.open-meteo.com").rstrip("/") + "/v1/search"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(url, params={"name": place[:80], "count": 1, "language": "en"}) as r:
                    r.raise_for_status()
                    found = (await r.json()).get("results") or []
        except (aiohttp.ClientError, TimeoutError) as e:
            raise HAError(f"the place lookup did not answer ({e})") from e
        if not found:
            raise HAError(f"I could not find a place called '{place}'.")
        f = found[0]
        region = f.get("admin1") if f.get("country_code") in ("US", "CA") else f.get("country")
        return f["latitude"], f["longitude"], ", ".join(x for x in (f.get("name"), region) if x)

    async def _fetch(self, lat=None, lon=None) -> dict:
        params = {"latitude": self.cfg["latitude"] if lat is None else lat,
                  "longitude": self.cfg["longitude"] if lon is None else lon,
                  "timezone": "auto", "forecast_days": 3,
                  "current": "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation",
                  "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                  "temperature_unit": "celsius" if self.metric else "fahrenheit",
                  "wind_speed_unit": "kmh" if self.metric else "mph"}
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(f"{self.base}/v1/forecast", params=params) as r:
                    r.raise_for_status()
                    return await r.json()
        except (aiohttp.ClientError, TimeoutError) as e:
            raise HAError(f"the weather service did not answer ({e})") from e

    async def report(self, when: str = "now", place: str = "") -> str:
        """The weather at home, or at `place` (any town, looked up by name)."""
        place = (place or "").strip()
        if place and norm(place) not in (norm(self.place), "here", "home"):
            lat, lon, where = await self._geocode(place)
            data = await self._fetch(lat, lon)
        else:
            where, data = self.place, await self._fetch()
        deg, spd = ("°C", "km/h") if self.metric else ("°F", "mph")
        w = norm(when)
        if w in ("", "now", "current", "currently", "today"):
            c, d = data["current"], data["daily"]
            self.card = {"where": where, "when": "Now", "cond": WMO.get(c["weather_code"], "unknown conditions"),
                         "temp": f"{round(c['temperature_2m'])}{deg}",
                         "line": f"High {round(d['temperature_2m_max'][0])}{deg} · Low {round(d['temperature_2m_min'][0])}{deg}"
                                 f" · {d['precipitation_probability_max'][0]}% precipitation"}
            out = (f"Right now in {where}: {WMO.get(c['weather_code'], 'unknown conditions')}, "
                   f"{round(c['temperature_2m'])}{deg} (feels like {round(c['apparent_temperature'])}{deg}), "
                   f"wind {round(c['wind_speed_10m'])} {spd}.")
            return out + f" Today: high {round(d['temperature_2m_max'][0])}{deg}, low {round(d['temperature_2m_min'][0])}{deg}, " \
                         f"{d['precipitation_probability_max'][0]}% chance of precipitation."
        idx = {"tomorrow": 1, "day after tomorrow": 2}.get(w)
        if idx is None:
            raise HAError("I can tell you the weather now, today, tomorrow, or the day after.")
        d = data["daily"]
        self.card = {"where": where, "when": when.capitalize(), "cond": WMO.get(d["weather_code"][idx], "unknown conditions"),
                     "temp": f"{round(d['temperature_2m_max'][idx])}{deg}",
                     "line": f"High {round(d['temperature_2m_max'][idx])}{deg} · Low {round(d['temperature_2m_min'][idx])}{deg}"
                             f" · {d['precipitation_probability_max'][idx]}% precipitation"}
        return (f"{when.capitalize()} in {where}: {WMO.get(d['weather_code'][idx], 'unknown conditions')}, "
                f"high {round(d['temperature_2m_max'][idx])}{deg}, low {round(d['temperature_2m_min'][idx])}{deg}, "
                f"{d['precipitation_probability_max'][idx]}% chance of precipitation.")
