"""
android_mcp.py - a minimal MCP server that lets Claude drive an Android phone over ADB.

Claude gets these tools:
  list_devices, screenshot, get_ui_elements, tap, tap_element, long_press, swipe,
  type_text, press_key, launch_app, stop_app, list_apps, install_apk, open_url,
  current_app, read_logcat, wait

How it works: every tool shells out to `adb` (Android platform-tools). The UI is read
with `uiautomator dump`, which returns every on-screen element with its text, id and
bounds, so Claude can tap things by name instead of guessing pixels. Screenshots come
back as images so Claude can also "see" the screen.

Requirements: Python 3.10+, `pip install "mcp>=1.2,<2" pillow`, adb on PATH,
a phone with USB debugging (or wireless debugging) enabled.

Pick a device with the env var ANDROID_SERIAL when more than one is connected.
"""

from __future__ import annotations

import io
import os
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

from mcp.server.fastmcp import FastMCP, Image

try:
    from PIL import Image as PILImage
except ImportError:  # screenshots still work, just not downscaled
    PILImage = None

ADB = os.environ.get("ADB_PATH", "adb")
SCREENSHOT_MAX_SIDE = int(os.environ.get("SCREENSHOT_MAX_SIDE", "1280"))

mcp = FastMCP("android-phone")


# ---------------------------------------------------------------- adb helpers
def _adb(*args: str, timeout: int = 30, binary: bool = False):
    cmd = [ADB]
    serial = os.environ.get("ANDROID_SERIAL")
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    proc = subprocess.run(cmd, capture_output=True, timeout=timeout)
    if proc.returncode != 0:
        err = proc.stderr.decode(errors="replace").strip()
        raise RuntimeError(f"adb {' '.join(args)} failed: {err}")
    return proc.stdout if binary else proc.stdout.decode(errors="replace")


def _shell(command: str, timeout: int = 30) -> str:
    return _adb("shell", command, timeout=timeout)


_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def _dump_ui() -> list[dict]:
    """Return a flat list of visible UI elements with centre coordinates."""
    xml = ""
    for _ in range(3):  # uiautomator occasionally fails while the UI is animating
        try:
            _shell("uiautomator dump /sdcard/window_dump.xml", timeout=20)
            xml = _shell("cat /sdcard/window_dump.xml")
            if "<hierarchy" in xml:
                break
        except RuntimeError:
            time.sleep(0.5)
    if "<hierarchy" not in xml:
        raise RuntimeError("Could not read the UI hierarchy (screen may be animating or secure).")
    xml = xml[xml.index("<?xml") if "<?xml" in xml else xml.index("<hierarchy"):]
    root = ET.fromstring(xml)
    elements = []
    for node in root.iter("node"):
        a = node.attrib
        m = _BOUNDS.match(a.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        text = a.get("text", "")
        desc = a.get("content-desc", "")
        rid = a.get("resource-id", "")
        clickable = a.get("clickable") == "true"
        editable = a.get("class", "").endswith("EditText")
        if not (text or desc or clickable or editable or a.get("scrollable") == "true"):
            continue  # skip pure layout containers
        elements.append({
            "text": text,
            "desc": desc,
            "id": rid.split("/")[-1] if rid else "",
            "class": a.get("class", "").split(".")[-1],
            "clickable": clickable,
            "scrollable": a.get("scrollable") == "true",
            "checked": a.get("checked") == "true",
            "focused": a.get("focused") == "true",
            "center": ((x1 + x2) // 2, (y1 + y2) // 2),
            "bounds": (x1, y1, x2, y2),
        })
    return elements


def _fmt(e: dict, i: int) -> str:
    label = " | ".join(p for p in (
        f'text="{e["text"]}"' if e["text"] else "",
        f'desc="{e["desc"]}"' if e["desc"] else "",
        f'id={e["id"]}' if e["id"] else "",
    ) if p)
    flags = ",".join(f for f, on in (
        ("clickable", e["clickable"]), ("scrollable", e["scrollable"]),
        ("checked", e["checked"]), ("focused", e["focused"])) if on)
    x, y = e["center"]
    return f"[{i}] {e['class']} {label} @({x},{y}) {flags}".rstrip()


# ---------------------------------------------------------------- tools
@mcp.tool()
def list_devices() -> str:
    """List Android devices/emulators visible to adb."""
    return subprocess.run([ADB, "devices", "-l"], capture_output=True, text=True).stdout


@mcp.tool()
def screenshot() -> Image:
    """Take a screenshot of the phone screen. Coordinates in the image may be scaled;
    prefer get_ui_elements() for exact tap coordinates."""
    png = _adb("exec-out", "screencap", "-p", binary=True, timeout=30)
    if PILImage is None:
        return Image(data=png, format="png")
    img = PILImage.open(io.BytesIO(png)).convert("RGB")
    img.thumbnail((SCREENSHOT_MAX_SIDE, SCREENSHOT_MAX_SIDE))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=70)
    return Image(data=buf.getvalue(), format="jpeg")


@mcp.tool()
def get_ui_elements(filter_text: str = "") -> str:
    """List on-screen UI elements (text, content description, resource id, centre
    coordinates in real screen pixels). Optional filter_text keeps only elements whose
    text/desc/id contains it (case-insensitive)."""
    els = _dump_ui()
    if filter_text:
        f = filter_text.lower()
        els = [e for e in els if f in (e["text"] + e["desc"] + e["id"]).lower()]
    size = _shell("wm size").strip()
    lines = [size] + [_fmt(e, i) for i, e in enumerate(els)]
    return "\n".join(lines) if els else size + "\n(no matching elements)"


@mcp.tool()
def tap(x: int, y: int) -> str:
    """Tap at real screen pixel coordinates."""
    _shell(f"input tap {x} {y}")
    return f"tapped ({x},{y})"


@mcp.tool()
def tap_element(text: str = "", resource_id: str = "", desc: str = "", index: int = 0) -> str:
    """Tap the UI element matching text, resource_id or content description
    (case-insensitive substring match; exact matches win). Use index when several match."""
    if not (text or resource_id or desc):
        return "Give at least one of text, resource_id, desc."
    els = _dump_ui()

    def score(e):
        s = 0
        for want, have in ((text, e["text"]), (resource_id, e["id"]), (desc, e["desc"])):
            if not want:
                continue
            if want.lower() == have.lower():
                s += 2
            elif want.lower() in have.lower():
                s += 1
            else:
                return -1
        return s

    matches = sorted((e for e in els if score(e) >= 0), key=score, reverse=True)
    if not matches:
        return "No element matched. Call get_ui_elements() to see what is on screen."
    if index >= len(matches):
        return f"Only {len(matches)} matches."
    e = matches[index]
    x, y = e["center"]
    _shell(f"input tap {x} {y}")
    return f"tapped {_fmt(e, index)}" + (f" ({len(matches)} matches)" if len(matches) > 1 else "")


@mcp.tool()
def long_press(x: int, y: int, duration_ms: int = 800) -> str:
    """Long-press at coordinates."""
    _shell(f"input swipe {x} {y} {x} {y} {duration_ms}")
    return f"long-pressed ({x},{y}) for {duration_ms}ms"


@mcp.tool()
def swipe(x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> str:
    """Swipe from (x1,y1) to (x2,y2). To scroll down a list, swipe upward
    (e.g. from y=1600 to y=600)."""
    _shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")
    return f"swiped ({x1},{y1})->({x2},{y2})"


@mcp.tool()
def type_text(text: str, submit: bool = False) -> str:
    """Type text into the focused field (tap the field first). Set submit=True to
    press Enter afterwards. ASCII only via adb; for emoji/non-Latin text use an
    ADB keyboard IME."""
    # `input text` needs spaces encoded as %s; shlex.quote protects shell metacharacters
    _shell("input text " + shlex.quote(text.replace(" ", "%s")))
    if submit:
        _shell("input keyevent KEYCODE_ENTER")
    return f"typed {text!r}" + (" + Enter" if submit else "")


KEYS = {
    "home": "KEYCODE_HOME", "back": "KEYCODE_BACK", "enter": "KEYCODE_ENTER",
    "recents": "KEYCODE_APP_SWITCH", "delete": "KEYCODE_DEL", "tab": "KEYCODE_TAB",
    "power": "KEYCODE_POWER", "volume_up": "KEYCODE_VOLUME_UP",
    "volume_down": "KEYCODE_VOLUME_DOWN", "menu": "KEYCODE_MENU",
    "wakeup": "KEYCODE_WAKEUP", "search": "KEYCODE_SEARCH",
}


@mcp.tool()
def press_key(key: str) -> str:
    """Press a key: home, back, enter, recents, delete, tab, power, volume_up,
    volume_down, menu, wakeup, search, or any raw KEYCODE_* name."""
    code = KEYS.get(key.lower(), key.upper())
    _shell(f"input keyevent {shlex.quote(code)}")
    return f"pressed {code}"


@mcp.tool()
def list_apps(filter_text: str = "", include_system: bool = False) -> str:
    """List installed app package names (third-party only unless include_system)."""
    out = _shell("pm list packages" + ("" if include_system else " -3"))
    pkgs = sorted(l.removeprefix("package:").strip() for l in out.splitlines() if l.strip())
    if filter_text:
        pkgs = [p for p in pkgs if filter_text.lower() in p.lower()]
    return "\n".join(pkgs) or "(none)"


@mcp.tool()
def launch_app(package: str) -> str:
    """Launch an app by package name (e.g. com.android.settings)."""
    out = _shell(f"monkey -p {shlex.quote(package)} -c android.intent.category.LAUNCHER 1")
    if "No activities found" in out:
        return f"Could not launch {package}: no launcher activity. Check list_apps()."
    time.sleep(1.5)
    return f"launched {package}"


@mcp.tool()
def stop_app(package: str) -> str:
    """Force-stop an app."""
    _shell(f"am force-stop {shlex.quote(package)}")
    return f"stopped {package}"


@mcp.tool()
def current_app() -> str:
    """Return the package/activity currently in the foreground."""
    out = _shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1")
    return out.strip() or "unknown"


@mcp.tool()
def install_apk(path_on_host: str) -> str:
    """Install (or reinstall) an APK from the computer running this server."""
    return _adb("install", "-r", "-g", path_on_host, timeout=300).strip()


@mcp.tool()
def open_url(url: str) -> str:
    """Open a URL or deep link on the phone."""
    _shell(f"am start -a android.intent.action.VIEW -d {shlex.quote(url)}")
    return f"opened {url}"


@mcp.tool()
def read_logcat(package: str = "", lines: int = 200, errors_only: bool = False) -> str:
    """Read recent logcat lines, optionally only for one app's process and/or only errors.
    Useful for spotting crashes while testing."""
    args = ["logcat", "-d", "-t", str(lines)]
    if errors_only:
        args += ["*:E"]
    if package:
        pid = _shell(f"pidof {shlex.quote(package)}").strip().split(" ")[0]
        if pid:
            args += [f"--pid={pid}"]
    return _adb(*args, timeout=30)[-20000:]


@mcp.tool()
def wait(seconds: float = 1.0) -> str:
    """Wait for animations or loading to finish."""
    time.sleep(min(seconds, 30))
    return f"waited {seconds}s"


if __name__ == "__main__":
    mcp.run()
