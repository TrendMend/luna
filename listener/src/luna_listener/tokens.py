"""The one HTTP call: LiveKit's standard token endpoint on the worker (see ../../docs/protocol.md)."""
import os

import aiohttp


class TokenError(Exception):
    pass


async def fetch_token(cfg: dict, *, purpose: str | None = None, attributes: dict[str, str] | None = None) -> tuple[str, str]:
    """-> (LiveKit server URL, participant token). `purpose="control"` asks for the standing control-room token."""
    server = cfg["server"]
    key = os.environ.get(server.get("access_key_env", "LUNA_ACCESS_KEY"), "")
    if not key:
        raise TokenError(f"set the {server.get('access_key_env', 'LUNA_ACCESS_KEY')} environment variable "
                         f"(create a key with: luna-web add-device {cfg['device']})")
    body: dict = {"participant_name": cfg["device"]}
    if purpose:
        body["purpose"] = purpose
    if attributes:
        body["participant_attributes"] = attributes
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
            async with s.post(server["token_url"], json=body, headers={"Authorization": f"Bearer {key}"}) as r:
                if r.status == 401:
                    raise TokenError("the worker rejected this device's access key")
                if r.status not in (200, 201):
                    raise TokenError(f"token request failed: HTTP {r.status}")
                data = await r.json()
    except (aiohttp.ClientError, TimeoutError) as e:
        raise TokenError(f"cannot reach the token service at {server['token_url']}: {e}") from e
    return data["server_url"], data["participant_token"]


def attributes_for(trigger) -> dict[str, str]:
    """Participant attributes that tell the agent how this conversation started."""
    attrs = {"luna.trigger": trigger.kind}
    if trigger.wake_word:
        attrs["luna.wake_word"] = trigger.wake_word
    if trigger.say:
        attrs["luna.say"] = trigger.say
    return attrs
