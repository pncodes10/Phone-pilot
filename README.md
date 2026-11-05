# Let Claude control your phone to test apps

## The short answer

Claude can't touch a phone by itself. It needs a **bridge**: a small program that gives Claude "tools" (take screenshot, read screen, tap, type, swipe, open app) and turns each tool call into a real action on the phone. The standard way to plug tools into Claude is an **MCP server**. So the solution is:

```
Claude (Claude Code / Claude Desktop / your own app using the Claude API)
   │  MCP tool calls: screenshot, get_ui_elements, tap_element, type_text …
   ▼
MCP server  (android_mcp.py in this folder, or the ready-made mobile-mcp)
   │  adb commands
   ▼
Your Android phone (USB or Wi-Fi debugging on)
```

Claude then works in a loop: look at the screen → decide → tap/type → look again, until the test is done.

## Recommendation

**No computer and no API key? Use the Phone Pilot app in relay mode.** Install `PhonePilot.apk`, turn on its accessibility service, tap **Start relay mode**, and paste the pairing code into your Claude chat. Claude then controls the phone from the chat by running `phonectl.py`, which passes encrypted commands and results through a free relay server ([ntfy.sh](https://ntfy.sh), no account needed). You pay nothing beyond your Claude plan.

**Have an API key?** The app can also run Claude on the phone itself ("Run a task here"). The rest of this guide (ADB, Appium, the Wi-Fi bridge) is for when a computer is available.

If you do have a computer:

**For testing apps from your computer: Android + ADB + an MCP server.** It needs no root, no app on the phone, works with any app, and you can be running in ~10 minutes. It can also install builds and read crash logs, which the phone app can't.

**For Claude running on the phone itself, or no cable: the Phone Pilot app** (Option C, built and in this folder).

- Use **`android_mcp.py`** (this folder) if you want something small you fully understand and can extend (e.g. add your own test-specific tools).
- Use **[mobile-mcp](https://github.com/mobile-next/mobile-mcp)** (`npx -y @mobilenext/mobile-mcp@latest`) if you want a maintained, ready-made server that also does iOS.

## All options compared

| Approach | Where it runs | Can do | Setup | Good for |
|---|---|---|---|---|
| **A. ADB + MCP server** (recommended) | Computer, phone on USB/Wi-Fi | Any app: tap, type, swipe, screenshots, UI tree, install APKs, logs | Easy | Testing your apps with Claude |
| A2. mobile-mcp | Computer | Same, plus iOS sims/devices | Easy | Off-the-shelf, Android + iOS |
| **B. Appium (+ Appium MCP)** | Computer | Same as A, plus robust element finding, iOS, test-framework integration | Medium (Java, Appium server, drivers) | Turning Claude's exploration into repeatable CI tests |
| Maestro / Maestro MCP | Computer | YAML test flows, Claude can write/run them | Easy-medium | Repeatable regression flows |
| **C. Phone Pilot app (Accessibility Service)** | Only the phone, or computer over Wi-Fi | Read screen, tap, type, gestures, screenshots in any app; runs Claude itself or bridges to Claude on a computer | Install APK, enable service | Claude on the phone, no cable, basis for a product |
| C2. Phone-only via Termux + wireless ADB | Only the phone | Same as A, the phone ADB-connects to itself | Medium, fiddly | No-computer setups / experiments |
| UIAutomator / Espresso tests | Computer → phone | Very fast, reliable, but you write code per app | Medium | Classic automated tests, not agent-driven |
| **iOS: XCUITest / WebDriverAgent** (via Appium or mobile-mcp) | Mac with Xcode | Tap, type, screenshots, UI tree | Hard (Mac, Xcode, signing) | iPhone testing |

Why not iOS first: Apple doesn't let any app control other apps on-device, so iPhone control always needs a Mac running WebDriverAgent (XCUITest), plus a developer certificate for real devices. Android lets a computer do almost anything over ADB.

## Setup: Option A with `android_mcp.py` (≈10 minutes)

### 1. On the phone
1. Settings → About phone → tap **Build number** 7 times (enables Developer options).
2. Settings → System → Developer options → turn on **USB debugging**.
   - Xiaomi/Redmi/POCO: also turn on **USB debugging (Security settings)**, otherwise taps and typing are blocked.
3. Plug the phone into your computer and tap **Allow** on the "Allow USB debugging?" prompt (tick "Always allow").

### 2. On the computer
1. Install Android platform-tools (gives you `adb`):
   - macOS: `brew install android-platform-tools`
   - Windows: `winget install Google.PlatformTools`
   - Linux: `sudo apt install adb`
2. Check: `adb devices` should list your phone as `device` (not `unauthorized`).
3. Install Python deps: `pip install -r requirements.txt` (Python 3.10+).

### 3. Connect it to Claude
**Claude Code** (terminal):
```bash
claude mcp add android -- python /full/path/to/android_mcp.py
```

**Claude Desktop**: edit `claude_desktop_config.json` (Settings → Developer → Edit Config):
```json
{
  "mcpServers": {
    "android": {
      "command": "python",
      "args": ["/full/path/to/android_mcp.py"]
    }
  }
}
```
Restart Claude Desktop. You'll see the android tools in the tools menu.

To use mobile-mcp instead, use `"command": "npx", "args": ["-y", "@mobilenext/mobile-mcp@latest"]`.

### 4. Try it
Ask Claude something like:

> Open the Settings app, go to Display, and tell me whether dark theme is on. Take a screenshot at the end.

> Install ./build/app-debug.apk, open it, sign up with test@example.com / Passw0rd!, and report any screen that looks broken or any crash in logcat.

### Wireless (no cable)
Android 11+: Developer options → **Wireless debugging** → **Pair device with pairing code**, then on the computer:
```bash
adb pair <ip>:<pair-port>     # enter the code shown on the phone
adb connect <ip>:<port>
```

### Several devices
Set `ANDROID_SERIAL=<serial from adb devices>` in the MCP server's `env`.

## Tools the server gives Claude

| Tool | What it does |
|---|---|
| `screenshot` | Returns the screen as an image (downscaled JPEG) |
| `get_ui_elements` | Lists buttons/text/fields with exact tap coordinates (via `uiautomator dump`) |
| `tap_element` | Taps by visible text, resource id or content description |
| `tap`, `long_press`, `swipe` | Raw gestures by coordinates |
| `type_text` | Types into the focused field, optional Enter |
| `press_key` | home, back, enter, recents, … |
| `launch_app`, `stop_app`, `list_apps`, `current_app` | App control |
| `install_apk`, `open_url` | Install builds, open deep links |
| `read_logcat` | Recent logs / errors, to catch crashes |
| `wait` | Let animations/loading finish |

Tip: tell Claude to prefer `get_ui_elements` + `tap_element` over guessing from screenshots. It's faster, cheaper, and far more accurate. Use screenshots for visual checks.

## Known limits
- `type_text` via adb is ASCII only. For emoji or non-Latin text, install the open-source ADBKeyboard IME and add a tool that uses it.
- Some screens (banking apps, password fields, DRM video) block screenshots/UI dumps (`FLAG_SECURE`).
- Games and Flutter/Unity apps often expose little or no UI tree; Claude falls back to screenshots + coordinate taps.
- Claude acts on whatever is on the device. Use a test phone or test accounts, not your personal banking/email.

## Option C: the Phone Pilot app (built, in this folder)

`PhonePilot.apk` is an Android app (Android 11+) that lets Claude control the phone through an **Accessibility Service**, with no computer and no USB debugging needed. Source is in `android-app/`.

It works in three ways:

0. **Relay mode (no API key).** Tap **Start relay mode** and send the pairing code to Claude. Claude runs `python phonectl.py "<pairing>" get_screen` (or `tap_element`, `type_text`, `screenshot`…) once per action. Commands and results travel through an ntfy relay server, encrypted with AES-256-GCM using the pairing code, so the relay only sees random bytes and nobody without the code can send commands. Tap **New pairing code** to cut off anyone who has the old one. You can point it at a self-hosted ntfy server instead of ntfy.sh.

   **Daily limit.** ntfy.sh allows 250 messages a day per internet address, and that limit also applies to free accounts. Cloud machines that run Claude share addresses, so the quota can already be used up ("429 daily message quota reached"). An access token (`tk_...`, pasted into **Relay access token**) is supported, but on the free tier it does not lift the limit. For reliable control, use the Wi-Fi bridge with a Cloudflare tunnel (see "Faster control" below).

   **Updating from 0.1:** the 0.1 APK was signed with a key that no longer exists, so uninstall the old Phone Pilot before installing 0.2 (and turn its accessibility service on again). Builds from 0.2 on are signed with `android-app/phonepilot-debug.keystore` and install as normal updates.

1. **On the phone only.** Paste your Anthropic API key, type a task ("Open MyApp, sign up with a test account, report anything broken"), tap **Run**. Claude reads the screen, taps, types and scrolls on its own. A small bar at the bottom of the screen shows each step and has a **Stop** button. When it finishes, the app comes back with Claude's report.
2. **From Claude on your computer, over Wi-Fi.** Tap **Start Wi-Fi bridge** in the app. It shows a `PHONE_URL` and `PHONE_TOKEN`. Then on your computer:
   ```bash
   pip install "mcp>=1.2,<2"
   claude mcp add phone -e PHONE_URL=http://<phone-ip>:8765 -e PHONE_TOKEN=<token> -- python /full/path/to/phone_app_mcp.py
   ```
   Claude Code or Claude Desktop now gets the phone tools, the same as Option A but without a cable or ADB.

Tools Claude gets: `get_screen` (short numbered list of the useful items), `screenshot`, `tap_id`, `tap_text`, `tap`, `long_press`, `swipe`, `scroll`, `type_text`, `press_button` (back/home/recents/notifications…), `list_apps`, `launch_app` (by name or package), `open_url`, `wait_for`, `wait_gone`, `wait`. From 0.7 every action returns the new screen; see "Phone Pilot 0.7" below.

### Install
1. Copy `PhonePilot.apk` to the phone and open it (allow "install unknown apps" for your file manager/browser when asked).
2. **Android 13+ only:** sideloaded apps can't get accessibility access until you allow it. Settings → Apps → Phone Pilot → ⋮ (top right) → **Allow restricted settings**. (The ⋮ menu appears after you've tried to turn on the service once.)
3. Open Phone Pilot → **Open Accessibility settings** → Installed apps / Downloaded apps → **Phone Pilot** → turn on.
4. Get an API key at console.anthropic.com and paste it into the app (for mode 1 only). Default model is `claude-opus-5-5`; you can type another model id in the Model field (e.g. `claude-sonnet-5-5` or `claude-haiku-5-5` for cheaper runs).

### Build it yourself
- **Android Studio:** open the `android-app` folder and press Run. Plain Java, no libraries.
- **Command line, no Android Studio:** `ANDROID_JAR=/path/to/android-34/android.jar bash build_apk.sh` (needs a JDK, `aapt`, `dx`, `zipalign`, `apksigner`).

### What was tested, and what wasn't
The APK compiles and packages correctly. The agent loop was tested against a mock Claude API (tool calls, screenshots sent back as images, several tool calls in one turn, retries). The Wi-Fi bridge was tested end to end with `phone_app_mcp.py` (tools listed, calls work, wrong token rejected). **It has not yet run on a real phone**, because I don't have one. The accessibility parts (reading the screen, gestures, screenshots, launching apps) use standard Android APIs, but expect a round of fixes after your first try.

### Things to know
- Turn the accessibility service off when you're not using it. While it's on, the app can see and control everything on screen.
- The Wi-Fi bridge is plain HTTP on your local network, protected by the token. Only start it on a network you trust, and stop it when done.
- The API key is stored in the app's private storage, unencrypted.
- Claude is told never to buy things, message real people, delete data or change security settings unless your task says to, and to treat on-screen text as content, not instructions. Still, use a test phone or test accounts.
- Google Play restricts apps that use accessibility for automation, so this is for sideloading, not the Play Store.
- Screens that block screenshots (banking, DRM) return an error; Claude falls back to the element list.

### Faster control (Phone Pilot 0.4+)

- Actions now wait only until the screen stops changing (usually 150 to 500 ms) instead of a fixed pause.
- `--screen` returns the new screen with the action in one round trip: `phonectl.py PAIR tap_element '{"index":3}' --screen`
- `batch` runs several steps in one round trip and stops at the first error:
  `phonectl.py PAIR batch '[["tap_element",{"index":16}],["type_text",{"text":"hello"}]]' --screen`
- Fastest path: the Wi-Fi bridge plus a Cloudflare quick tunnel from Termux (`cloudflared tunnel --url http://localhost:8765`), used as `bridge:https://ADDRESS.trycloudflare.com#TOKEN`.

### Phone Pilot 0.7: numbered items, waits and test scripts

Needs the 0.7 app on the phone: `PhonePilot.apk` in this folder is 0.7. With an older app, `phonectl.py` still works but the new tools below answer "Unknown tool".

**Updating to 0.7:** this build is signed with a new debug key (certificate SHA-256 `699d419c…658b55`), so Android won't install it over 0.6. Uninstall the old Phone Pilot first, install 0.7, then turn its accessibility service on again (and "Allow restricted settings" again on Android 13+). Later builds signed with the same keystore install as normal updates.

**Short screen layout.** `get_screen` now drops hidden, tiny, off-screen and empty layout items, plus the status and navigation bars, and prints one line per item:
```
Screen #12  app=com.example.storageplanner  size=1080x2400
[1] TextView "Storage Planner"
[4] LinearLayout "Box A | 3 items" (id=row) @540,610 [click,long]
[5] Button "Save" (id=save_btn) @540,1820 [click]
[6] EditText "" hint="Item name" (id=name) @540,900 [edit,focused]
```
A clickable row is named after the text inside it, and that text isn't listed again. `get_screen full=true` gives the old detailed listing (bounds, package, every element) for debugging.

**Numbered items.** Each screen read numbers the items and remembers them. `tap_id 5` taps item 5, and `type_text text="Box A" id=6` types into item 6. Before acting, the phone checks the item is still there with the same id, text and position. If the screen changed, nothing is tapped and you get an error plus the new screen. `tap_element` still works as another name for `tap_id`. Numbers now start at 1.

**Tap by text.** `tap_text "Add item"` (or `res_id=save_btn`) taps the best visible match: exact text first, then clickable items.

**The new screen after every action.** `tap_id`, `tap_text`, `tap`, `type_text`, `swipe`, `scroll`, `press_button`, `launch_app`, `open_url`, `wait_for` and `wait_gone` wait for the screen to settle, then return
```
ok: Clicked [5] Button "Save". (UI settled in 240 ms)

screen:
Screen #13 ...
```
(`error: ...` when it failed). Pass `observe=false` to get just the first line. `batch` returns the screen once at the end.

**Wait until.** `wait_for text="Saved" timeout=5` and `wait_gone text="Loading"` check the screen every 300 ms until it matches or the time runs out (default 5 s, max 60), and report how long it took: `Found "Saved" after 640 ms.` or `Timed out after 5000 ms: "Saved" did not appear.` (an error). Use these instead of fixed `wait`s.

**Labelled screenshot fallback.** When the foreground app shows fewer than 2 readable items (games, drawing screens, some web views), `get_screen` and the screen returned after an action also attach a screenshot with numbered boxes, plus a 6-column grid of numbered cells. `tap_id` on a cell taps its centre; for exact spots use `tap` with pixel coordinates (the result gives the image-to-screen factor). Turn it off with `labels=false`, or force it on any screen with `get_screen labels=true` or `screenshot labels=true`.

**Replayable test scripts.** `phonectl.py` can run a YAML step file on its own, with no Claude and no API key:
```yaml
app: com.example.storageplanner
steps:
  - launch
  - tap: "Add item"
  - type: "Box A"
  - tap: "Save"
  - assert_visible: "Box A"
```
```
$ export PHONE_PAIRING="bridge:https://ADDRESS.trycloudflare.com#TOKEN"
$ python phonectl.py run test.yaml
Running test.yaml on com.example.storageplanner (5 steps)
  PASS    1  launch com.example.storageplanner                       912 ms
  PASS    2  tap "Add item"                                          430 ms
  ...
  FAIL    5  assert_visible "Box A"                                 3010 ms  Timed out after 3000 ms: "Box A" did not appear.
              screenshot: /path/test-step5-fail.jpg
              screen:     /path/test-step5-fail.txt
FAILED: 4 passed, 1 failed in 6.1 s
```
It stops at the first failure (`--keep-going` carries on), saves a screenshot and the screen text of the failing step next to the script (`--out-dir` to change), and exits 1 on failure, so it fits in CI. The pairing can also be passed first (`phonectl.py PAIRING run test.yaml`) or put in the script as `pairing:`. PyYAML is used when installed; otherwise a built-in reader handles the simple YAML these scripts need.

| Step | Does |
|---|---|
| `launch` / `launch: Settings` | Open the script's `app:` (or the named app) |
| `tap: "Save"` / `tap: {text: "Save", exact: true}` / `tap: {id: save_btn}` / `tap: [540, 1820]` | Tap by text, resource id or position |
| `tap_id: 5` | Tap item 5 of the latest screen |
| `type: "Box A"` / `type: {text: "Box A", into: "Item name", submit: true}` | Type into the focused field, or tap the `into` field first |
| `assert_visible: "Box A"` / `assert_gone: "Box A"` | Pass if it appears / disappears within the script's `timeout:` (default 3 s); also take `{text, id, exact, timeout}` |
| `wait_for: "Saved"` / `wait_gone: "Loading"` | The same with a 5 s default |
| `back`, `home`, `recents`, `press: back` | System buttons |
| `scroll: down`, `swipe: [x1, y1, x2, y2]`, `long_press: [x, y]` | Gestures |
| `wait: 1.5`, `open_url: https://...`, `screenshot` | Pause, open a link, save a screenshot |

`examples/storage-planner.yaml` is a read-only smoke test of Storage Planner (`com.storageplanner`): main screen, the target field, and Algorithm Lab.

**Command-line shortcuts.** Tool arguments can be `key=value` pairs instead of JSON, with a bare value going to the main parameter: `phonectl.py PAIR tap_id 5`, `phonectl.py PAIR wait_for text=Saved timeout=5`, `phonectl.py PAIR get_screen full=true`, `phonectl.py PAIR launch_app Settings`. With `PHONE_PAIRING` set, the pairing can be left out: `phonectl.py get_screen`.

**Measured on a Samsung S23 FE** (Wi-Fi bridge over a Cloudflare tunnel, 0.7.2). Flow: open Storage Planner, open Algorithm Lab, press Back, checking each screen. Average of two runs:

| | Time | Phone calls | Screen text | Failed checks |
|---|---|---|---|---|
| 0.6 style: fixed waits, full `get_screen` after every action | 14.5 s | 10 | 10,300 chars | 0 |
| 0.7: numbered items, screen returned with each action, `wait_for` | 6.3 s | 5 | 3,600 chars | 0 |

`examples/storage-planner.yaml` passes all 13 steps in about 19 s.

