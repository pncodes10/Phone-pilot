"""
phonectl.py - send one command to a phone running Phone Pilot, or run a test script on it.

This is how Claude (e.g. in a Claude chat with a code sandbox) controls the phone without an
API key: it runs this script once per action.

    python phonectl.py PAIRING get_screen
    python phonectl.py PAIRING tap_id 5
    python phonectl.py PAIRING wait_for text="Saved" timeout=5
    python phonectl.py PAIRING type_text '{"text": "Box A", "id": 3}'
    python phonectl.py PAIRING screenshot --out shot.jpg
    python phonectl.py run test.yaml            # replay a test script, no Claude needed

PAIRING is the text the app shows, e.g. "phonepilot:https://ntfy.sh#3fa9..." or
"bridge:https://ADDRESS#TOKEN". You can also put it in the PHONE_PAIRING environment variable and
pass "-" or leave it out.

Arguments are JSON, or key=value pairs (values are read as JSON when they parse, else as text).
A bare value goes to the tool's main parameter: tap_id 5, launch_app Settings, scroll down.

Test scripts (YAML):

    app: com.example.storageplanner
    steps:
      - launch
      - tap: "Add item"
      - type: "Box A"
      - tap: "Save"
      - assert_visible: "Box A"

Each step prints PASS or FAIL with its time; a failing step saves a screenshot and the screen
text next to the script. See README.md for every step type.

Needs: Python 3.9+, `pip install cryptography` (relay mode only), PyYAML optional.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import sys
import re
import time
import urllib.request

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # only relay mode needs it; the Wi-Fi bridge does not
    AESGCM = None


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


def _aes(key: bytes):
    if AESGCM is None:
        raise SystemExit("Relay mode needs the cryptography package: pip install cryptography")
    return AESGCM(key)


def encrypt(key: bytes, data: bytes) -> str:
    nonce = secrets.token_bytes(12)
    return base64.b64encode(nonce + _aes(key).encrypt(nonce, data, None)).decode()


def decrypt(key: bytes, b64: str) -> bytes:
    raw = base64.b64decode(b64)
    return _aes(key).decrypt(raw[:12], raw[12:], None)


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


def send(pairing: str, name: str, args: dict, timeout: float = 90) -> dict:
    """One tool call over whichever transport the pairing text names."""
    fn = call_bridge if pairing.strip().startswith("bridge:") else call
    return fn(pairing, name, args, timeout)


def result_text(res: dict) -> str:
    return "\n".join(b.get("text", "") for b in res.get("content", []) if b.get("type") == "text")


def save_image(res: dict, path: str) -> str | None:
    for block in res.get("content", []):
        if block.get("type") == "image":
            with open(path, "wb") as f:
                f.write(base64.b64decode(block["source"]["data"]))
            return os.path.abspath(path)
    return None


# ---------------------------------------------------------------------------- command-line arguments

# The parameter a bare value fills, e.g. "tap_id 5" -> {"id": 5}. True means keep it as text.
MAIN_PARAM = {
    "tap_id": ("id", False), "get_text": ("id", False), "tap_element": ("index", False), "tap_text": ("text", True),
    "type_text": ("text", True), "launch_app": ("app", True), "open_url": ("url", True),
    "scroll": ("direction", True), "press_button": ("button", True), "wait": ("seconds", False),
    "wait_for": ("text", True), "wait_gone": ("text", True), "list_apps": ("filter", True),
}
# Tools whose bare values fill several parameters in order: "tap 540 1820".
POSITIONAL = {"tap": ("x", "y"), "long_press": ("x", "y"), "swipe": ("x1", "y1", "x2", "y2")}
TEXT_KEYS = {"text", "res_id", "app", "url", "direction", "button", "filter", "labels"}


def _value(key: str, raw: str):
    if key in TEXT_KEYS and not (raw.startswith('"') and raw.endswith('"')):
        return raw
    try:
        return json.loads(raw)
    except ValueError:
        return raw


def parse_args_text(tool: str, parts: list[str]):
    """JSON, or key=value tokens plus at most one bare value for the tool's main parameter."""
    if not parts:
        return {}
    if len(parts) == 1 and parts[0].lstrip()[:1] in "{[":
        return json.loads(parts[0])
    args: dict = {}
    bare: list[str] = []
    for p in parts:
        m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$", p, re.S)
        if m:
            args[m.group(1)] = _value(m.group(1), m.group(2))
        else:
            bare.append(p)
    if bare and tool in POSITIONAL:
        for key, raw in zip(POSITIONAL[tool], bare):
            args.setdefault(key, _value(key, raw))
        bare = bare[len(POSITIONAL[tool]):]
    if bare:
        if tool not in MAIN_PARAM:
            raise SystemExit(f"{tool} takes key=value arguments, not {' '.join(bare)!r}")
        key, as_text = MAIN_PARAM[tool]
        raw = " ".join(bare)
        args.setdefault(key, raw if as_text else _value(key, raw))
    return args


def looks_like_pairing(s: str) -> bool:
    return s == "-" or s.startswith(("phonepilot:", "bridge:", "http://", "https://"))


# ---------------------------------------------------------------------------- test scripts

def load_script(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        text = f.read()
    try:
        import yaml  # type: ignore
        data = yaml.safe_load(text)
    except ImportError:
        data = mini_yaml(text)
    if isinstance(data, list):
        data = {"steps": data}
    if not isinstance(data, dict) or not isinstance(data.get("steps"), list):
        raise SystemExit(f"{path}: expected a 'steps:' list")
    return data


def mini_yaml(text: str):
    """Enough YAML for test scripts when PyYAML isn't installed: mappings, lists, quoted and plain
    scalars, and inline {a: 1} / [1, 2]."""
    lines = []
    for raw in text.splitlines():
        stripped = _strip_comment(raw).rstrip()
        if stripped.strip():
            lines.append((len(stripped) - len(stripped.lstrip(" ")), stripped.strip()))
    pos = 0

    def block(indent: int):
        nonlocal pos
        if pos >= len(lines):
            return None
        if lines[pos][1].startswith("-") and (lines[pos][1] == "-" or lines[pos][1][1] == " "):
            out = []
            while pos < len(lines) and lines[pos][0] == indent and (lines[pos][1] == "-" or lines[pos][1].startswith("- ")):
                rest = lines[pos][1][1:].strip()
                if not rest:
                    pos += 1
                    out.append(block(lines[pos][0]) if pos < len(lines) and lines[pos][0] > indent else None)
                elif _is_key(rest):
                    lines[pos] = (indent + 2, rest)  # "- key: v" starts a mapping one level in
                    out.append(block(indent + 2))
                else:
                    pos += 1
                    out.append(scalar(rest))
            return out
        out = {}
        while pos < len(lines) and lines[pos][0] == indent and _is_key(lines[pos][1]):
            key, _, rest = lines[pos][1].partition(":")
            key, rest = key.strip().strip("\"'"), rest.strip()
            pos += 1
            if rest:
                out[key] = scalar(rest)
            elif pos < len(lines) and (lines[pos][0] > indent or (lines[pos][0] == indent and lines[pos][1].startswith("- "))):
                out[key] = block(lines[pos][0])
            else:
                out[key] = None
        return out

    return block(lines[0][0]) if lines else {}


def _strip_comment(line: str) -> str:
    q = None
    for i, c in enumerate(line):
        if q:
            if c == q:
                q = None
        elif c in "\"'":
            q = c
        elif c == "#" and (i == 0 or line[i - 1] in " \t"):
            return line[:i]
    return line


def _is_key(s: str) -> bool:
    return bool(re.match(r"""^("[^"]*"|'[^']*'|[^\s"'{\[][^:]*?):(\s|$)""", s))


def scalar(s: str):
    s = s.strip()
    if s[:1] in "{[":
        val, rest = _flow(s, 0)
        return val
    if s[:1] == '"':
        return json.loads(s)
    if s[:1] == "'":
        return s[1:-1].replace("''", "'")
    low = s.lower()
    if low in ("true", "yes", "on"):
        return True
    if low in ("false", "no", "off"):
        return False
    if low in ("null", "~"):
        return None
    try:
        return int(s)
    except ValueError:
        pass
    try:
        return float(s)
    except ValueError:
        return s


def _flow(s: str, i: int):
    """Parse an inline {..} or [..] starting at s[i]; returns (value, index after it)."""
    open_c = s[i]
    close_c = "}" if open_c == "{" else "]"
    out: dict | list = {} if open_c == "{" else []
    i += 1
    while True:
        while i < len(s) and s[i] in " ,":
            i += 1
        if i >= len(s):
            raise SystemExit(f"unclosed {open_c} in {s!r}")
        if s[i] == close_c:
            return out, i + 1
        if isinstance(out, dict):
            j = i
            while s[j] not in ":":
                j += 1
            key = s[i:j].strip().strip("\"'")
            val, i = _flow_value(s, j + 1, close_c)
            out[key] = val
        else:
            val, i = _flow_value(s, i, close_c)
            out.append(val)


def _flow_value(s: str, i: int, close_c: str):
    while s[i] == " ":
        i += 1
    if s[i] in "{[":
        return _flow(s, i)
    if s[i] in "\"'":
        q, j = s[i], i + 1
        while s[j] != q or (q == '"' and s[j - 1] == "\\"):
            j += 1
        return scalar(s[i:j + 1]), j + 1
    j = i
    while j < len(s) and s[j] not in "," + close_c:
        j += 1
    return scalar(s[i:j]), j


def _arg(value, key: str) -> dict:
    """A step's value as tool arguments: a mapping is used as is, anything else fills key."""
    if isinstance(value, dict):
        return dict(value)
    return {} if value is None else {key: value}


def plan_step(step, app: str | None, default_timeout: float) -> tuple[str, list[tuple[str, dict]]]:
    """Turn one script step into (description, [(tool, args), ...])."""
    if isinstance(step, str):
        kind, value = step.strip(), None
    elif isinstance(step, dict) and len(step) == 1:
        kind, value = next(iter(step.items()))
    else:
        raise ValueError(f"a step is a word or a single key: value, not {step!r}")
    kind = kind.strip().lower()
    label = kind if value is None else f"{kind} {json.dumps(value, ensure_ascii=False)}"

    if kind in ("launch", "launch_app", "open_app"):
        target = value if isinstance(value, str) else (value or {}).get("app") if isinstance(value, dict) else None
        target = target or app
        if not target:
            raise ValueError("launch needs an app: put 'app: <package>' at the top of the script")
        return f"launch {target}", [("launch_app", {"app": target})]
    if kind == "tap":
        if isinstance(value, list) and len(value) == 2:
            return label, [("tap", {"x": value[0], "y": value[1]})]
        if isinstance(value, dict) and "x" in value:
            return label, [("tap", {"x": value["x"], "y": value["y"]})]
        args = _arg(value, "text")
        if "id" in args and "res_id" not in args:
            args["res_id"] = args.pop("id")
        return label, [("tap_text", args)]
    if kind == "tap_id":
        return label, [("tap_id", _arg(value, "id"))]
    if kind in ("long_press", "hold"):
        x, y = (value if isinstance(value, list) else (value["x"], value["y"]))
        return label, [("long_press", {"x": x, "y": y})]
    if kind == "type":
        args = _arg(value, "text")
        calls = []
        into = args.pop("into", None)
        if into is not None:
            calls.append(("tap_text", {"text": str(into)}))
        calls.append(("type_text", args))
        return label, calls
    if kind in ("assert_visible", "wait_for", "assert_text"):
        args = _arg(value, "text")
        if "id" in args and "res_id" not in args:
            args["res_id"] = args.pop("id")
        args.setdefault("timeout", default_timeout if kind != "wait_for" else 5)
        return label, [("wait_for", args)]
    if kind in ("assert_gone", "assert_not_visible", "wait_gone"):
        args = _arg(value, "text")
        if "id" in args and "res_id" not in args:
            args["res_id"] = args.pop("id")
        args.setdefault("timeout", default_timeout if kind != "wait_gone" else 5)
        return label, [("wait_gone", args)]
    if kind in ("back", "home", "recents", "notifications"):
        return label, [("press_button", {"button": kind})]
    if kind == "press":
        return label, [("press_button", _arg(value, "button"))]
    if kind == "scroll":
        return label, [("scroll", _arg(value or "down", "direction"))]
    if kind == "swipe":
        if isinstance(value, list) and len(value) == 4:
            return label, [("swipe", dict(zip(("x1", "y1", "x2", "y2"), value)))]
        return label, [("swipe", _arg(value, "x1"))]
    if kind == "wait":
        return label, [("wait", _arg(value if value is not None else 1, "seconds"))]
    if kind == "open_url":
        return label, [("open_url", _arg(value, "url"))]
    if kind == "screenshot":
        return label, [("screenshot", {})]
    raise ValueError(f"unknown step '{kind}'")


def run_script(pairing: str, path: str, keep_going: bool, out_dir: str | None, timeout: float) -> int:
    script = load_script(path)
    app = script.get("app")
    default_timeout = float(script.get("timeout", 3))
    steps = script["steps"]
    base = os.path.splitext(os.path.basename(path))[0]
    out_dir = out_dir or os.path.dirname(os.path.abspath(path))
    title = script.get("name") or os.path.basename(path)
    print(f"Running {title}" + (f" on {app}" if app else "") + f" ({len(steps)} steps)")
    passed = failed = 0
    t_all = time.time()
    for n, step in enumerate(steps, 1):
        t0 = time.time()
        try:
            label, calls = plan_step(step, app, default_timeout)
        except (ValueError, KeyError, TypeError) as e:
            print(f"  FAIL  {n:>3}  {step!r}  bad step: {e}")
            failed += 1
            if not keep_going:
                break
            continue
        ok, detail = True, ""
        for tool, args in calls:
            args = {**args, "observe": False}
            try:
                res = send(pairing, tool, args, timeout)
            except (SystemExit, OSError, ValueError) as e:
                res = {"is_error": True, "content": [{"type": "text", "text": f"no answer from the phone: {e}"}]}
            text = result_text(res).strip()
            if tool == "screenshot" and not res.get("is_error"):
                shot = save_image(res, os.path.join(out_dir, f"{base}-step{n}.jpg"))
                detail = f"saved {shot}"
            if res.get("is_error"):
                ok = False
                detail = text.splitlines()[0] if text else "failed"
                if text.startswith("Unknown tool"):
                    detail += " (this step needs Phone Pilot 0.7 or newer on the phone)"
                break
            if not detail and tool in ("wait_for", "wait_gone"):
                detail = text.splitlines()[0] if text else ""
        ms = int((time.time() - t0) * 1000)
        line = f"  {'PASS' if ok else 'FAIL'}  {n:>3}  {label[:60]:<60} {ms:>6} ms"
        print(line + (f"  {detail}" if detail else ""))
        if ok:
            passed += 1
            continue
        failed += 1
        try:
            shot = save_image(send(pairing, "screenshot", {}, timeout), os.path.join(out_dir, f"{base}-step{n}-fail.jpg"))
            screen = result_text(send(pairing, "get_screen", {"labels": False}, timeout))
            txt = os.path.join(out_dir, f"{base}-step{n}-fail.txt")
            with open(txt, "w", encoding="utf-8") as f:
                f.write(screen)
            print(f"              screenshot: {shot or '(none: ' + 'screen blocks screenshots?)'}")
            print(f"              screen:     {os.path.abspath(txt)}")
        except (SystemExit, OSError, ValueError) as e:
            print(f"              (could not save the failing screen: {e})")
        if not keep_going:
            break
    skipped = len(steps) - passed - failed
    print(f"{'PASSED' if failed == 0 else 'FAILED'}: {passed} passed, {failed} failed"
          + (f", {skipped} skipped" if skipped else "") + f" in {time.time() - t_all:.1f} s")
    return 0 if failed == 0 else 1


# ---------------------------------------------------------------------------- main

def main() -> None:
    argv = sys.argv[1:]
    env_pairing = os.environ.get("PHONE_PAIRING", "")
    # The pairing may be left out when PHONE_PAIRING is set: "phonectl.py get_screen".
    if argv and not argv[0].startswith("-") and not looks_like_pairing(argv[0]):
        argv = ["-"] + argv

    if len(argv) >= 2 and argv[1] == "run":
        ap = argparse.ArgumentParser(prog="phonectl.py [PAIRING] run",
                                     description="Run a test script and print PASS/FAIL for each step.")
        ap.add_argument("pairing")
        ap.add_argument("cmd")
        ap.add_argument("script", help="test script (YAML)")
        ap.add_argument("--keep-going", action="store_true", help="carry on after a failed step")
        ap.add_argument("--out-dir", help="where failure screenshots go (default: next to the script)")
        ap.add_argument("--timeout", type=float, default=90, help="seconds to wait for each phone answer")
        a = ap.parse_args(argv)
        pairing = env_pairing if a.pairing == "-" else a.pairing
        if not pairing:
            pairing = str(load_script(a.script).get("pairing") or "")
        if not pairing:
            raise SystemExit("No pairing: pass it first, set PHONE_PAIRING, or add 'pairing:' to the script.")
        sys.exit(run_script(pairing, a.script, a.keep_going, a.out_dir, a.timeout))

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pairing", help='pairing text from the app, or "-" (or nothing) to read PHONE_PAIRING')
    ap.add_argument("tool", help="get_screen, tap_id, tap_text, type_text, wait_for, wait_gone, screenshot, tap, "
                                 "swipe, scroll, press_button, list_apps, launch_app, open_url, long_press, "
                                 "wait, batch, or 'run SCRIPT'")
    ap.add_argument("input", nargs="*", help="tool arguments as JSON, or key=value pairs")
    ap.add_argument("--out", default="screenshot.jpg", help="where to save a screenshot")
    ap.add_argument("--timeout", type=float, default=90)
    ap.add_argument("--screen", action="store_true",
                    help="after the action, also return the new screen (actions do this by default from 0.7)")
    a = ap.parse_args(argv)
    pairing = env_pairing if a.pairing == "-" else a.pairing
    if not pairing:
        raise SystemExit("No pairing: pass it first or set PHONE_PAIRING.")
    tool = a.tool
    args = parse_args_text(tool, a.input)
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
            texts.append(f"{i}. {st['name']}: " + result_text(r))
            if r.get("is_error"):
                break
        else:
            r = send(pairing, "get_screen", {}, a.timeout) if args.get("screen") else {"content": []}
            texts.append(result_text(r))
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
