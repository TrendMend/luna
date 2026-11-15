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
        self.card: list[dict] = []  # the last report, for the card on the person's screen

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
        out, self.card = [], []
        for label, spec in picks:
            try:
                st = await self.ha.state(spec["entity"])
            except HAError:
                st = None
            state = (st or {}).get("state")
            if state not in ("on", "off"):
                out.append(f"{label}: I can't tell right now")  # never guess "closed" for an unknown sensor
                self.card.append({"label": label, "state": "unknown"})
                continue
            if spec.get("invert"):
                state = "off" if state == "on" else "on"
            word = WORDS[spec.get("kind", "contact")][state]
            out.append(f"{label}: {word}")
            self.card.append({"label": label, "state": word})
        return "; ".join(out)


WMO = {0: "clear sky", 1: "mostly clear", 2: "partly cloudy", 3: "overcast", 45: "foggy", 48: "foggy with frost",
       51: "light drizzle", 53: "drizzle", 55: "heavy drizzle", 56: "freezing drizzle", 57: "freezing drizzle",
       61: "light rain", 63: "rain", 65: "heavy rain", 66: "freezing rain", 67: "freezing rain",
       71: "light snow", 73: "snow", 75: "heavy snow", 77: "snow grains", 80: "light showers", 81: "showers",
       82: "heavy showers", 85: "snow showers", 86: "heavy snow showers", 95: "thunderstorms",
       96: "thunderstorms with hail", 99: "thunderstorms with heavy hail"}

ICONS = {0: "☀️", 1: "🌤️", 2: "⛅", 3: "☁️", 45: "🌫️", 48: "🌫️",
         51: "🌦️", 53: "🌦️", 55: "🌧️", 56: "🌧️", 57: "🌧️",
         61: "🌦️", 63: "🌧️", 65: "🌧️", 66: "🌧️", 67: "🌧️",
         71: "🌨️", 73: "🌨️", 75: "❄️", 77: "🌨️", 80: "🌦️",
         81: "🌧️", 82: "⛈️", 85: "🌨️", 86: "❄️", 95: "⛈️",
         96: "⛈️", 99: "⛈️"}


def icon(code) -> str:
    return ICONS.get(code, "🌡️")


def forecast_days(d: dict, deg: str, limit: int = 5) -> list[dict]:
    """The next few days for the card: short day name, icon, high, low, chance of precipitation."""
    import datetime as dt
    out = []
    for i, code in enumerate((d.get("weather_code") or [])[:limit]):
        try:
            name = "Today" if i == 0 else dt.date.fromisoformat(d["time"][i]).strftime("%a")
        except (KeyError, IndexError, ValueError):
            name = "Today" if i == 0 else f"+{i}"
        out.append({"day": name, "icon": icon(code), "hi": f"{round(d['temperature_2m_max'][i])}°",
                    "lo": f"{round(d['temperature_2m_min'][i])}°", "rain": f"{d['precipitation_probability_max'][i]}%"})
    return out


COMPASS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
ARROWS = ["↑", "↗", "→", "↘", "↓", "↙", "←", "↖"]     # drawn the way Google's weather card does: the arrow points at the compass name


def compass_index(degrees) -> int:
    """0..7 for N, NE, ... NW: the direction the wind comes FROM (Open-Meteo's `wind_direction_10m`)."""
    return int(((float(degrees) % 360) + 22.5) // 45) % 8


def hour_label(iso: str) -> str:
    """'2026-11-07T17:00' -> '5 PM'."""
    import datetime as dt
    h = dt.datetime.fromisoformat(iso).hour
    return f"{h % 12 or 12} {'AM' if h < 12 else 'PM'}"


def hour_start(times: list, wanted: str) -> int:
    """Index of the first hourly time at or after `wanted` (an ISO time; only its date and hour count), or len(times)."""
    key = (wanted or "")[:13]
    return next((i for i, t in enumerate(times) if str(t)[:13] >= key), len(times))


def hourly_slots(h: dict, start: int, deg: str, spd: str, rain: str, hours: int = 12) -> list[dict]:
    """The next `hours` hours for the card: time, icon, chance of rain, temperature, rain amount, wind speed and direction.
    An hour with a missing value is left out rather than shown wrong."""
    out = []
    for i in range(start, min(start + hours, len(h.get("time") or []))):
        try:
            code, night = h["weather_code"][i], h.get("is_day", [1] * (i + 1))[i] == 0
            out.append({
                "time": hour_label(h["time"][i]),
                "icon": "🌙" if night and code in (0, 1) else icon(code),
                "temp": f"{round(h['temperature_2m'][i])}°",
                "pop": f"{round(h['precipitation_probability'][i])}%",
                "rain": f"{float(h['precipitation'][i]):.2f} {rain}",
                "wind": f"{round(h['wind_speed_10m'][i])} {spd}",
                "dir": COMPASS[compass_index(h["wind_direction_10m"][i])],
                "arrow": ARROWS[compass_index(h["wind_direction_10m"][i])],
            })
        except (KeyError, IndexError, TypeError, ValueError):
            continue
    return out


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
                  "timezone": "auto", "forecast_days": 5,
                  "current": "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,precipitation",
                  "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,"
                           "wind_speed_10m_max,wind_direction_10m_dominant",
                  "hourly": "temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_direction_10m,is_day",
                  "temperature_unit": "celsius" if self.metric else "fahrenheit",
                  "wind_speed_unit": "kmh" if self.metric else "mph",
                  "precipitation_unit": "mm" if self.metric else "inch"}
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(f"{self.base}/v1/forecast", params=params) as r:
                    r.raise_for_status()
                    return await r.json()
        except (aiohttp.ClientError, TimeoutError) as e:
            raise HAError(f"the weather service did not answer ({e})") from e

    def _extras(self, data: dict, idx: int, now: bool) -> dict:
        """What the tabbed card adds to the card: the day's name, the hourly strip, the day's rain, the wind, and the other unit's label.
        Every part is optional: a missing one just means that part is left out (the card falls back to the plain layout)."""
        import datetime as dt
        deg, spd, rain = ("°C", "km/h", "mm") if self.metric else ("°F", "mph", "in")
        c, d, h = data.get("current") or {}, data.get("daily") or {}, data.get("hourly") or {}
        extras: dict = {"alt_temp": "°F" if self.metric else "°C", "alt_wind": "mph" if self.metric else "km/h",
                        "alt_rain": "in" if self.metric else "mm"}
        try:
            iso = c["time"] if now else d["time"][idx]
            extras["day"] = dt.date.fromisoformat(str(iso)[:10]).strftime("%A")
            times = h.get("time") or []
            start = hour_start(times, iso) if now else hour_start(times, f"{str(iso)[:10]}T06")   # tomorrow: from the morning
            slots = hourly_slots(h, start, deg, spd, rain)
            if slots:
                extras["hourly"] = slots
        except (KeyError, IndexError, TypeError, ValueError):
            pass
        try:
            extras["rain_day"] = f"{float(d['precipitation_sum'][idx]):.2f} {rain}"
        except (KeyError, IndexError, TypeError, ValueError):
            pass
        try:
            speed, heading = (c["wind_speed_10m"], c["wind_direction_10m"]) if now else \
                (d["wind_speed_10m_max"][idx], d["wind_direction_10m_dominant"][idx])
            extras["wind_now"] = f"{round(speed)} {spd}"
            extras["wind_from"] = COMPASS[compass_index(heading)]
        except (KeyError, IndexError, TypeError, ValueError):
            pass
        return extras

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
                         "icon": icon(c["weather_code"]), "temp": f"{round(c['temperature_2m'])}{deg}",
                         "feels": f"Feels like {round(c['apparent_temperature'])}{deg} · Wind {round(c['wind_speed_10m'])} {spd}",
                         "days": forecast_days(d, deg),
                         "line": f"High {round(d['temperature_2m_max'][0])}{deg} · Low {round(d['temperature_2m_min'][0])}{deg}"
                                 f" · {d['precipitation_probability_max'][0]}% precipitation",
                         **self._extras(data, 0, now=True)}
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
                     "icon": icon(d["weather_code"][idx]), "temp": f"{round(d['temperature_2m_max'][idx])}{deg}",
                     "days": forecast_days(d, deg),
                     "line": f"High {round(d['temperature_2m_max'][idx])}{deg} · Low {round(d['temperature_2m_min'][idx])}{deg}"
                             f" · {d['precipitation_probability_max'][idx]}% precipitation",
                     **self._extras(data, idx, now=False)}
        return (f"{when.capitalize()} in {where}: {WMO.get(d['weather_code'][idx], 'unknown conditions')}, "
                f"high {round(d['temperature_2m_max'][idx])}{deg}, low {round(d['temperature_2m_min'][idx])}{deg}, "
                f"{d['precipitation_probability_max'][idx]}% chance of precipitation.")
