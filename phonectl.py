"""
phonectl.py - send one command to a phone running Phone Pilot in relay mode, and print the result.

This is how Claude (e.g. in a Claude chat with a code sandbox) controls the phone without an
API key: it runs this script once per action.

    python phonectl.py PAIRING get_screen
    python phonectl.py PAIRING tap_element '{"index": 3}'
    python phonectl.py PAIRING screenshot --out shot.jpg

PAIRING is the text the app shows, e.g. "phonepilot:https://ntfy.sh#3fa9...". You can also put
it in the PHONE_PAIRING environment variable and pass "-" instead.

Needs: Python 3.9+, `pip install cryptography`.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import sys
import time
import urllib.request

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def parse_pairing(text: str) -> tuple[str, bytes, str]:
    """Returns (server, key, access_token). Format: phonepilot:SERVER[?auth=TOKEN]#HEXKEY"""
    text = text.strip()
    if text.startswith("phonepilot:"):
        text = text[len("phonepilot:"):]
    server, _, code = text.rpartition("#")
    token = ""
    if "?auth=" in server:
        server, _, token = server.partition("?auth=")
    key = bytes.fromhex(code)
    if len(key) != 32 or not server.startswith("http"):
        raise SystemExit("Bad pairing code. Copy it again from the Phone Pilot app.")
    return server.rstrip("/"), key, token.strip()


def topic_id(key: bytes) -> str:
    return "pp" + hashlib.sha256(b"phonepilot-topic" + key).hexdigest()[:30]


def encrypt(key: bytes, data: bytes) -> str:
    nonce = secrets.token_bytes(12)
    return base64.b64encode(nonce + AESGCM(key).encrypt(nonce, data, None)).decode()


def decrypt(key: bytes, b64: str) -> bytes:
    raw = base64.b64decode(b64)
    return AESGCM(key).decrypt(raw[:12], raw[12:], None)


_opener = urllib.request.build_opener()


def _get(url: str, timeout: float, headers: dict | None = None) -> bytes:
    with _opener.open(urllib.request.Request(url, headers=headers or {}), timeout=timeout) as r:
        return r.read()


def call(pairing: str, name: str, args: dict, timeout: float = 90) -> dict:
    server, key, token = parse_pairing(pairing)
    auth = {"Authorization": f"Bearer {token}"} if token else {}
    tid = topic_id(key)
    req_id = secrets.token_hex(6)
    since = int(time.time()) - 2
    body = encrypt(key, json.dumps({"id": req_id, "t": int(time.time()), "name": name, "input": args}).encode())
    data = body.encode()
    headers = {"Cache": "yes", **auth}
    if len(data) > 3500:
        headers["Filename"] = "msg.txt"
    _opener.open(urllib.request.Request(f"{server}/{tid}-cmd", data=data, method="PUT", headers=headers), timeout=30).read()

    deadline = time.time() + timeout
    url = f"{server}/{tid}-res/json?since={since}"
    while time.time() < deadline:
        try:
            with _opener.open(urllib.request.Request(url, headers=auth), timeout=min(60, max(5, deadline - time.time()))) as r:
                for line in r:
                    line = line.strip()
                    if not line:
                        continue
                    ev = json.loads(line)
                    if ev.get("event") != "message":
                        continue
                    payload = ev.get("message", "")
                    if ev.get("attachment", {}).get("url"):
                        payload = _get(ev["attachment"]["url"], 60).decode()
                    try:
                        res = json.loads(decrypt(key, payload.strip()))
                    except Exception:
                        continue
                    if res.get("id") == req_id:
                        return res
                    url = f"{server}/{tid}-res/json?since={ev['id']}"
                    if time.time() > deadline:
                        break
        except (TimeoutError, OSError):
            continue
    raise SystemExit(f"No answer from the phone within {timeout:.0f}s. Is relay mode on and the phone online?")


def call_bridge(target: str, name: str, args: dict, timeout: float = 90) -> dict:
    """Direct mode: talk to the app's Wi-Fi bridge, e.g. through a Cloudflare tunnel.
    target = "bridge:https://xyz.trycloudflare.com#BRIDGE_TOKEN"."""
    url, _, token = target[len("bridge:"):].strip().rpartition("#")
    if not url.startswith("http") or not token:
        raise SystemExit('Bad bridge address. Use "bridge:https://ADDRESS#TOKEN".')
    req = urllib.request.Request(url.rstrip("/") + "/call", method="POST",
                                 data=json.dumps({"name": name, "input": args}).encode(),
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with _opener.open(req, timeout=timeout) as r:
        return json.loads(r.read())


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pairing", help='pairing text from the app, or "-" to read PHONE_PAIRING')
    ap.add_argument("tool", help="get_screen, screenshot, tap_element, tap, swipe, scroll, type_text, "
                                 "press_button, list_apps, launch_app, open_url, long_press, wait")
    ap.add_argument("input", nargs="?", default="{}", help="tool arguments as JSON")
    ap.add_argument("--out", default="screenshot.jpg", help="where to save a screenshot")
    ap.add_argument("--timeout", type=float, default=90)
    ap.add_argument("--screen", action="store_true",
                    help="after the action, also return the new screen (one round trip with Phone Pilot 0.4+)")
    a = ap.parse_args()
    pairing = os.environ.get("PHONE_PAIRING", "") if a.pairing == "-" else a.pairing
    send = call_bridge if pairing.strip().startswith("bridge:") else call
    tool, args = a.tool, json.loads(a.input)
    # "batch" takes a list of [name, input] pairs (or {"name","input"} objects).
    if tool == "batch" and isinstance(args, list):
        args = {"steps": [{"name": s[0], "input": s[1] if len(s) > 1 else {}} if isinstance(s, list) else s for s in args]}
    if a.screen and tool != "get_screen":
        if tool != "batch":
            args = {"steps": [{"name": tool, "input": args}]}
            tool = "batch"
        args["screen"] = True
    res = send(pairing, tool, args, a.timeout)
    if tool == "batch" and res.get("is_error") and "Unknown tool batch" in json.dumps(res):
        # Older Phone Pilot: run the steps one by one.
        texts = []
        for i, st in enumerate(args["steps"], 1):
            r = send(pairing, st["name"], st.get("input", {}), a.timeout)
            texts.append(f"{i}. {st['name']}: " + " ".join(b.get("text", "") for b in r.get("content", []) if b.get("type") == "text"))
            if r.get("is_error"):
                break
        else:
            r = send(pairing, "get_screen", {}, a.timeout) if args.get("screen") else {"content": []}
            texts.append("".join(b.get("text", "") for b in r.get("content", [])))
        res = {"content": [{"type": "text", "text": "\n".join(texts)}], "is_error": False}
    for block in res.get("content", []):
        if block.get("type") == "image":
            with open(a.out, "wb") as f:
                f.write(base64.b64decode(block["source"]["data"]))
            print(f"[screenshot saved to {os.path.abspath(a.out)}]")
        elif block.get("type") == "text":
            print(block["text"])
    sys.exit(1 if res.get("is_error") else 0)


if __name__ == "__main__":
    main()
