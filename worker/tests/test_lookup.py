import asyncio

import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

from luna.ha import HAError
from luna.info import Weather
from luna.search import PREFIX, WebSearch


def serve(app):
    loop = asyncio.new_event_loop()
    srv = TestServer(app)
    loop.run_until_complete(srv.start_server())
    return loop, srv, str(srv.make_url("")).rstrip("/")


def test_weather_other_place_and_home():
    seen = {}

    async def geo(req):
        seen["name"] = req.query["name"]
        if req.query["name"] == "Nowhereville":
            return web.json_response({})
        return web.json_response({"results": [{"name": "Vancouver", "admin1": "British Columbia", "country_code": "CA",
                                               "latitude": 49.2, "longitude": -123.1}]})

    async def forecast(req):
        seen["lat"] = req.query["latitude"]
        # the real service answers 400 to anything that is not a variable (it once rejected "time"): check what we ask for
        bad = set(req.query["daily"].split(",")) - {"weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_probability_max"}
        if bad:
            return web.json_response({"error": True, "reason": f"Invalid value: {sorted(bad)}"}, status=400)
        return web.json_response({"current": {"temperature_2m": 9.0, "apparent_temperature": 7.0, "weather_code": 61,
                                              "wind_speed_10m": 10, "precipitation": 1},
                                  "daily": {"time": ["2026-11-07", "2026-11-08", "2026-11-09"], "weather_code": [61, 3, 0], "temperature_2m_max": [11, 12, 13], "temperature_2m_min": [5, 6, 7],
                                            "precipitation_probability_max": [70, 20, 0]}})
    app = web.Application()
    app.router.add_get("/v1/search", geo)
    app.router.add_get("/v1/forecast", forecast)
    loop, srv, base = serve(app)
    w = Weather({"base_url": base, "geocoding_url": base, "name": "Calgary", "latitude": 51.0, "longitude": -114.0})
    out = loop.run_until_complete(w.report("now", "Vancouver"))
    assert "Vancouver, British Columbia" in out and seen["lat"] == "49.2"
    loop.run_until_complete(w.report("now", "home"))
    assert seen["lat"] == "51.0"                                   # home never geocodes
    with pytest.raises(HAError, match="could not find"):
        loop.run_until_complete(w.report("now", "Nowhereville"))
    loop.run_until_complete(srv.close())


@pytest.mark.parametrize("provider", ["brave", "tavily", "searxng"])
def test_search_providers(provider, monkeypatch):
    monkeypatch.setenv("K", "secret")

    async def brave(req):
        assert req.headers["X-Subscription-Token"] == "secret"
        return web.json_response({"web": {"results": [{"title": "A", "url": "https://a.example", "description": "alpha  beta"},
                                                      {"title": "bad", "url": "javascript:x", "description": "no"}]}})

    async def tavily(req):
        assert req.headers["Authorization"] == "Bearer secret" and (await req.json())["query"] == "q"
        return web.json_response({"results": [{"title": "A", "url": "https://a.example", "content": "alpha beta"}]})

    async def searx(req):
        assert req.query["format"] == "json"
        return web.json_response({"results": [{"title": "A", "url": "https://a.example", "content": "alpha beta"}]})
    app = web.Application()
    app.router.add_get("/res/v1/web/search", brave)
    app.router.add_post("/search", tavily)
    app.router.add_get("/search", searx)
    loop, srv, base = serve(app)
    s = WebSearch({"provider": provider, "api_key_env": "K", "base_url": base})
    out = loop.run_until_complete(s.report("q"))
    assert out.startswith(PREFIX) and "https://a.example" in out and "alpha beta" in out and "javascript" not in out
    loop.run_until_complete(srv.close())


def test_search_failure_is_reported():
    s = WebSearch({"provider": "searxng", "base_url": "http://127.0.0.1:1"})
    with pytest.raises(HAError):
        asyncio.run(s.report("q"))
    with pytest.raises(ValueError):
        WebSearch({"provider": "bing"})
