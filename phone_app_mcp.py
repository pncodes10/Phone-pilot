"""
phone_app_mcp.py - connects Claude Code / Claude Desktop on your computer to the
Phone Pilot app over Wi-Fi. No USB cable or ADB needed.

On the phone: open Phone Pilot, turn on the accessibility service, tap
"Start Wi-Fi bridge". It shows PHONE_URL and PHONE_TOKEN.

On the computer:
    pip install "mcp>=1.2,<2"
    claude mcp add phone -e PHONE_URL=http://192.168.1.23:8765 -e PHONE_TOKEN=... \
        -- python /full/path/to/phone_app_mcp.py

The tool list comes from the phone itself, so it always matches the app version.
"""

from __future__ import annotations

import asyncio
import json
import os
import urllib.error
import urllib.request

import mcp.types as types
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server

PHONE_URL = os.environ.get("PHONE_URL", "").rstrip("/")
PHONE_TOKEN = os.environ.get("PHONE_TOKEN", "")

server = Server("phone-pilot")


def _request(method: str, path: str, payload: dict | None = None, timeout: float = 60) -> dict:
    if not PHONE_URL or not PHONE_TOKEN:
        raise RuntimeError("Set PHONE_URL and PHONE_TOKEN (shown in the Phone Pilot app).")
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        PHONE_URL + path, data=data, method=method,
        headers={"Authorization": f"Bearer {PHONE_TOKEN}", "Content-Type": "application/json"},
    )
    # The phone is on the local network; never send this through an HTTP proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        raise RuntimeError(f"Phone answered {e.code}: {body}") from None
    except urllib.error.URLError as e:
        raise RuntimeError(
            f"Cannot reach the phone at {PHONE_URL} ({e.reason}). Is the Wi-Fi bridge on and "
            "are both devices on the same network?") from None


@server.list_tools()
async def list_tools() -> list[types.Tool]:
    data = await asyncio.to_thread(_request, "GET", "/tools")
    return [
        types.Tool(name=t["name"], description=t["description"], inputSchema=t["input_schema"])
        for t in data["tools"]
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[types.TextContent | types.ImageContent]:
    data = await asyncio.to_thread(_request, "POST", "/call", {"name": name, "input": arguments or {}})
    out: list[types.TextContent | types.ImageContent] = []
    for block in data.get("content", []):
        if block.get("type") == "image":
            src = block["source"]
            out.append(types.ImageContent(type="image", data=src["data"], mimeType=src["media_type"]))
        elif block.get("type") == "text":
            out.append(types.TextContent(type="text", text=block["text"]))
    if data.get("is_error"):
        # Surface tool errors to Claude as errors.
        raise RuntimeError(" ".join(b.text for b in out if isinstance(b, types.TextContent)) or "tool failed")
    return out


async def main() -> None:
    async with stdio_server() as (read, write):
        await server.run(read, write, server.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
