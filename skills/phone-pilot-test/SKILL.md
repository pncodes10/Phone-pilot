---
name: "phone-pilot-test"
description: "Drive pranesh's Android phone through the Phone Pilot app (Wi-Fi bridge + Cloudflare tunnel) to open, tap through, time and test apps being built, run replayable YAML test scripts, and read app logs."
---

# Testing apps on the phone with Phone Pilot

Phone Pilot is an Accessibility Service app on the user's Android phone (Samsung S23 FE, 1080x2340). Its Wi-Fi bridge (port 8765, Bearer token) is exposed to the internet by a Cloudflare quick tunnel running in Termux. You control the phone with `phonectl.py`, one command per call, about 0.6-2 s each.

Source: https://github.com/pncodes10/Phone-pilot (usually checked out at /home/user/Phone-pilot; if not, `ls -d /home/user/*/phonectl.py`). The prebuilt `PhonePilot.apk` is in the repo root. This skill is written for **Phone Pilot 0.10+**; with an older app the new tools answer "Unknown tool": ask the user to install the current `PhonePilot.apk`.

## 0. Tell the user the process first

Before you start (and whenever you don't yet have a working address and token), tell the user these steps in plain words, as a short numbered list:

Each time you want something tested:
1. On the phone, start Phone Pilot's Wi-Fi bridge and run the `cloudflared` command in Termux: `cloudflared tunnel --url http://localhost:8765`
2. In any thread, give the agent the tunnel address and token, and say what to test.
3. **Follow the chat from another device (laptop or tablet), not the phone.** Leave the phone unlocked, on the app being tested, and untouched while it works. Opening the Claude app on the phone moves the foreground app under the agent, and its taps land in the chat instead. The strip at the top left of the phone shows what the agent is doing, and when it needs you, the phone rings like an alarm and shows an orange "Claude needs you" card: do what it says, then tap **Done**.

Then wait for the address and token before sending any command. If the user already gave them in this conversation, skip straight to connecting. The user may call the token "phone password".

## 1. Get connected

1. The address looks like `https://<words>.trycloudflare.com` and the token is a long hex string. The address changes every time the tunnel restarts; the token can change when the app is reinstalled. Never save either to memory or commit them.
2. On the phone the user must: have Phone Pilot's accessibility service on, tap **Start Wi-Fi bridge**, and keep `cloudflared` running in Termux.
3. The cloud environment must allow `*.trycloudflare.com` (with the star) under Allowed domains. A proxy `403` on CONNECT means it doesn't; send the user to the environment settings.
4. Set the pairing once and test it:

```bash
export PHONE_PAIRING="bridge:https://ADDRESS.trycloudflare.com#TOKEN"
C="timeout 70 python3 /home/user/Phone-pilot/phonectl.py"   # the repo checkout; find it with: ls -d /home/user/*/phonectl.py /mnt/project-files/*/phonectl.py
$C get_screen --timeout 60
```

With `PHONE_PAIRING` set, the pairing argument can be left out. Needs Python 3.9+ (`cryptography` only for the old ntfy relay mode).

Error meanings: `401` = wrong/changed token. `502 Bad Gateway` = tunnel up but the bridge is off or busy (wait 5-10 s and retry; if it persists, ask the user to restart the bridge). Response starting `[The phone's screen is off.]` or `[The phone is on the lock screen.]` = ask the user to unlock; you cannot unlock it.

### Quick start (copy this)

```bash
export PHONE_PAIRING="bridge:https://ADDRESS.trycloudflare.com#TOKEN"
export PHONE_APP="com.example.notes"          # guard: taps refuse when another app is in front
C="timeout 100 python3 /home/user/Phone-pilot/phonectl.py"
$C status "Notes: add a note"                 # task line on the phone
$C launch_app com.example.notes               # returns the screen, numbered
$C tap_text "New note"                        # tap by visible text
$C type_text text="Shopping" id=6             # type into item [6] from the latest screen
$C tap_text Save
$C wait_for text="Shopping" timeout=5         # check, without fixed sleeps
$C ask_user "Please sign in, then tap Done" wait=80   # ring the person when blocked
```

## 2. Reading the screen

`get_screen` returns a short numbered list, one item per line:

```
Screen #12  app=com.example.notes  size=1080x2340
Keyboard open from y=1332 (type_text doesn't need it; press back to hide it)
[5] Button "Save" (id=save_btn) @540,1820 [click]
[6] EditText "" hint="Title" (id=title) @540,1521 [click,long,edit,focused]
```

- The header's `app=` is the foreground app. **Check it before acting**; if it is not the app under test (e.g. `com.anthropic.claude`), stop and tell the user (section 7). `PHONE_APP` makes the phone refuse actions in the wrong app for you.
- Coordinates (`@x,y`) are real screen pixels; use them directly with `tap`.
- Hidden, tiny, off-screen and empty layout items, the status and navigation bars, and the keyboard's keys are left out. A clickable row is named after the text inside it.
- Text is cut at 80 characters. Use `get_text` (section 3) for the full text, e.g. logs.
- `get_screen full=true` gives the old detailed listing (bounds, package, every element) for debugging.
- If the app shows fewer than 2 readable items (games, canvases, a screen still loading), a **labelled screenshot** is attached with numbered boxes and a 6-column grid of numbered cells; `tap_id` on a cell taps its centre. Add `labels=false` to any call to turn this off (do this in scripted loops: screenshots cost about 1 s). `get_screen labels=true` forces it.

## 3. Commands

Arguments are `key=value` pairs or JSON; a bare value fills the main parameter.

| Command | Does |
|---|---|
| `get_screen` | Numbered screen list (above) |
| `tap_id 5` | Tap item 5 of the latest screen. **Refuses** (nothing tapped, new screen returned) if the item moved or changed since it was numbered. |
| `tap_text "Save"` / `tap_text text=Save exact=true` / `tap_text res_id=save_btn` | Tap the best visible match: exact text first, then clickable. Matches text, description and hint, **in every window, including other apps and the nav bar** (e.g. `"Back"` exact matches the system Back key). |
| `type_text text="Box A" id=6` | Type into item 6 (replaces its text; `append=true` keeps it, `submit=true` presses Enter). Without `id`, types into the focused field. |
| `wait_for text="Saved" timeout=5` | Poll every 300 ms until the text is on screen; an error on timeout. Reports how long it took. |
| `wait_for text="Run" exact=true enabled=true timeout=60` | Wait **on the phone** for a button to come back after the app's work (no polling over the tunnel). `enabled=false` waits for it to grey out. |
| `wait_gone text="Loading" timeout=10` | Same as `wait_for`, until the text disappears. |
| `scroll_to text="Result log"` | Swipe until the item is on screen (`direction=up`, `max=10`), stopping at the end of the list. One call instead of scroll-and-look loops. |
| `get_text 4` / `get_text text="Compress 2026"` | **Full, uncut** text of one item (up to 200,000 chars). The first line of the output is a `<n> characters:` header: save with `$C get_text ... \| tail -n +2 > log.txt`. Only finds items currently on screen: scroll to them first. |
| `scroll down` | Swipes inside the app's largest scrollable area, above the keyboard. |
| `tap 540 1820`, `long_press 540 1820`, `swipe 100 1800 100 600` | Gestures in real pixels |
| `press_button back` (home, recents, notifications) | System buttons |
| `launch_app com.example.notes` / `launch_app Settings` | Open an app; waits until it is in front |
| `screenshot --out file.jpg` (then Read the jpg) | Visual check. `labels=true` draws the numbered boxes. Multiply image coords by the printed factor for taps. |
| `ask_user "Please pick a photo in the picker" wait=60` | **Ask the person for help** (pick a file, unlock, sign in, confirm by eye). Rings like an alarm until Done (alarm tone and vibration, even on silent; at most `ring=30` s). `alert=sound` (one notification sound), `alert=vibrate` or `alert=none` are quieter: use them for low-priority notes. It shows an orange card with **Done** and a notification with **Done** (works on the lock screen and in system screens). `wait=N` (max 80) blocks until Done; `ask_user wait=60` keeps waiting; `ask_user clear=true` withdraws it. |
| `status "Login test 3/8"` | Task line shown on the phone above the current action. Set it at the start of each test; `status ""` clears it at the end. |
| `in_app=com.example.notes` on any tap/type/gesture, or `export PHONE_APP=...` once | **Guard**: the action is refused (nothing done, new screen returned) when another app is in front. Comma-separate extra packages: **include the file picker (`com.google.android.documentsui`) whenever the task opens it**, or the guard refuses the steps around it, e.g. `PHONE_APP=com.example.notes,com.google.android.documentsui`. |
| `list_apps "storage"` | Find an app's package from its name: prints `Storage Planner  (com.storageplanner)`, one app per line. `launch_app` also takes the plain name. |
| `open_url`, `wait seconds=1` | Misc. Prefer `wait_for` over fixed `wait`. |
| `batch '[["tap_id",{"id":5}],["type_text",{"text":"hi"}]]'` | Several steps in one round trip, stops at the first error |

**The phone shows what you are doing.** Every command updates a small strip at the top left of the phone ("Claude: Tapping \"Save\"…", then ✓ or ✗) with a Stop button. Your taps pass through it, it hides itself on system screens (file picker, permission and install dialogs) and after 60 s without commands, and `get_screen` never lists it. Stop ends the bridge: if commands suddenly fail to connect, the user may have pressed it.

**Every action returns the new screen** after it settles, as `ok: <message>` (or `error: ...`), a blank line, `screen:`, then the screen list. So you rarely need a separate `get_screen`. Add `observe=false` to get only the first line (faster when you don't need the screen, e.g. inside loops).

### Go fast

Each `phonectl` call costs about 0.6-1 s of tunnel time, plus the time the screen takes to settle. Most of a run is spent on calls you didn't need:
- **Chain several commands in one Bash call**, with a check between them, instead of one tool call per command. Stop the chain when a result says `error:` or `app=` is wrong (see the scenarios).
- **Read the returned screen.** Every action already returns it; don't follow an action with `get_screen`.
- **Tap by text** (`tap_text Save`) when you know the label: no need to read the screen first. Use `tap_id` for items without a unique label.
- **Let the phone wait.** `wait_for ... enabled=true`, `wait_gone` and `scroll_to` loop on the phone, every 300 ms, in one call. A Bash loop of `get_screen` calls costs a second per check.
- **`observe=false labels=false`** for steps whose screen you don't need (fixed navigation you have done before, timing runs).
- **`batch`** a known sequence (`[["tap_text",{"text":"Edit"}],["type_text",{"text":"x"}],["tap_text",{"text":"Save"}]]`): one round trip, stops at the first error, returns one screen at the end.
- **Write a test script** (section 4) for anything you'll run twice. It runs with no thinking between steps.
- **Ask early.** If a step needs the person (picker, sign-in, unlock), `ask_user` at once instead of trying taps that can't work.

### How text matching works

`tap_text`, `wait_for`, `wait_gone`, `scroll_to` and `get_text text=` all match the same way: **case-insensitive "contains"** against an item's text, its description, and (for fields) its hint. So a short word can hit the wrong item: `scroll_to text="Compress"` also matches a button whose description says "compress", and returns at once. Use the full visible label, add `exact=true` (all five commands take it), or match `res_id=` instead.

### Numbers are only valid for the screen that gave them out

- Any screen read renumbers: every action's returned screen, `get_screen`, and `get_screen full=true` (whose numbers differ from the short list). Always take numbers from the **latest** output.
- If `tap_id` says "The screen has changed since screen #N was read", that is the safety check working: read the numbers from the screen it returned and try again. Don't fall back to raw coordinates.
- When a page can scroll between your read and your tap, prefer `tap_text` for named buttons.

## 4. Replayable test scripts

`phonectl.py run test.yaml` runs a YAML step file with no Claude involvement and prints PASS/FAIL with the time for each step. It stops at the first failure (`--keep-going` to continue), saves the failing step's screenshot and screen text next to the script (`--out-dir` to change), and exits 1 on failure.

```yaml
app: com.example.notes
timeout: 4          # seconds each assert_visible / assert_gone may wait (default 3)
steps:
  - launch
  - wait_gone: "Loading"
  - tap: "New note"
  - type: {text: "Shopping", into: "Title"}
  - back                         # hide the keyboard before tapping things under it
  - tap: "Save"
  - assert_visible: "Shopping"
```

Steps: `launch`, `tap: "text"` / `tap: {text, id, exact}` / `tap: [x, y]`, `tap_id: 5`, `type: "text"` / `type: {text, into, submit}`, `assert_visible`, `assert_gone`, `wait_for`, `wait_gone`, `back`, `home`, `press: back`, `scroll: down`, `swipe: [x1, y1, x2, y2]`, `long_press: [x, y]`, `wait: 1.5`, `open_url`, `screenshot`.

Write a script whenever a flow will be repeated (regression checks, before/after comparisons). Use plain commands for exploring a new screen.

## 5. Timing and performance checks

When asked to time steps:
- Wrap each call and log elapsed wall time, e.g.
  `t(){ l=$1; shift; s=$(date +%s%3N); $C "$@" > /tmp/last.txt; echo "$l: $(( $(date +%s%3N)-s )) ms"; cat /tmp/last.txt; }`
- Use `observe=false` / `labels=false` for steps whose screen you don't need, so you time the app, not screenshots.
- For long work in the app (a button greys out while it runs), tap it with `observe=false`, then time one `wait_for text=<button> exact=true enabled=true timeout=60 observe=false` call: it returns as soon as the button is back. Also quote the app's own reported duration if its log has one.
- Report a table: step, time, result. Say which part is the app and which is the tunnel and settle overhead (each Phone Pilot call costs about 0.6-1 s through the tunnel).
- For reference: a three-screen flow with checks took 6.3 s and 5 calls the new way (actions return the screen, `wait_for`), against 14.5 s and 10 calls with fixed waits and a full `get_screen` after each action.

## 6. Worked scenarios

### Scenario A: check a new build of an app, then leave a regression script

The user: "I installed the new Notes build. Check that adding a note works, and keep a test I can rerun."

```bash
export PHONE_PAIRING="bridge:https://ADDRESS.trycloudflare.com#TOKEN" PHONE_APP=com.example.notes
C="timeout 100 python3 /home/user/Phone-pilot/phonectl.py"

# 1. Explore once, reading each returned screen (one Bash call, stops at the first error).
$C status "Notes: add-a-note check"
$C launch_app com.example.notes labels=false | tee /tmp/s.txt | head -30
grep -q "^error" /tmp/s.txt && exit 1
$C tap_text "New note" labels=false | head -30
```

Read the screen that came back, pick the title field's number, then continue:

```bash
$C type_text text="Shopping" id=6 observe=false
$C press_button back observe=false              # hide the keyboard
$C tap_text Save labels=false | head -20
$C wait_for text="Shopping" timeout=5 observe=false
```

```bash
# 2. Turn what worked into a script, and run it to prove it passes.
cat > notes-add.yaml <<'YAML'
app: com.example.notes
steps:
  - launch
  - tap: "New note"
  - type: {text: "Shopping", into: "Title"}
  - back
  - tap: "Save"
  - assert_visible: "Shopping"
YAML
python3 /home/user/Phone-pilot/phonectl.py run notes-add.yaml
$C status ""
```

Report: the PASS/FAIL table and the time for each step, what you saw, and the script's path, so the user can rerun it with `phonectl.py run notes-add.yaml`.

### Scenario B: run a long in-app job that needs a file, time it, and collect the log

The user: "Run the compress test on a camera photo, time each step, and send me the log."

```bash
export PHONE_PAIRING=... PHONE_APP=com.example.app,com.google.android.documentsui
C="timeout 100 python3 /home/user/Phone-pilot/phonectl.py"
t(){ l=$1; shift; s=$(date +%s%3N); $C "$@" > /tmp/last.txt; echo "$l: $(( $(date +%s%3N)-s )) ms"; }

$C status "Compress test"
t "open app"      launch_app com.example.app labels=false
t "open lab"      tap_text "Algorithm Lab" labels=false
t "pick pictures" tap_text "Pick pictures" labels=false
```

The system file picker ignores automated taps, so ring the person and wait:

```bash
t "user picks"    ask_user "Please tap one camera photo in the picker (or the file you want tested), then tap Done" wait=80
t "check"         get_screen labels=false; grep "1 of 1" /tmp/last.txt     # which file was picked?
t "find button"   scroll_to text="Compress (test copy)" observe=false
t "start"         tap_text "Compress (test copy)" exact=true observe=false
t "job runs"      wait_for text="Compress (test copy)" exact=true enabled=true timeout=60 observe=false
t "find log"      scroll_to text="Compress 2026" observe=false
$C get_text text="Compress 2026" | tail -n +2 > compress-log.txt   # full text; drop the "<n> characters:" header
$C status ""
```

Report: a table of step times (say which part was the app's own work: the job's time also appears in its log), the result lines from the log, the log file, and anything odd (e.g. the app picked a different file than expected).

## 7. Known pitfalls

- **The user watching on the phone.** If `app=` shows `com.anthropic.claude` (or anything other than the app under test and its pickers), stop immediately. Don't tap: `tap_text` searches every window and can hit buttons in the user's chat. Tell the user what happened and ask them to watch from another device. To get their attention on the phone instead, use `ask_user` (it notifies over any app).
- **Long-running work in an app.** Buttons often show `disabled` while it runs: `wait_for text=<button> exact=true enabled=true timeout=60`. Results shown in one long text block need `scroll_to`, then `get_text`.
- **Samsung DeX or a second screen.** If the phone shows a black touchpad (items with ids like `touchpad_close_image_button`) or `app=` is empty after launching, the app opened on the other display. Don't tap the touchpad's close button: `ask_user` the person to end DeX and open the app on the phone. Right after switching, a screen read can come back empty once; read it again.
- **Leftover keyboard.** A field focused in an earlier run keeps the keyboard open, which hides buttons near the bottom (the header shows `Keyboard open from y=...`). `press_button back` hides it. `press_button back` on an app's root screen exits the app.
- **The relaunch resets the app.** `launch_app` can bring an app back on its main screen, losing in-app state such as a log on screen. Read logs with `get_text` before switching away.
- **System file picker (`com.google.android.documentsui`).** It ignores automated taps and accessibility clicks on files (seen on Android 11-15 by other automation tools too; Edge panels off didn't help). Opening it from the app (e.g. tapping **Pick pictures**) is fine; it is taps on files *inside* the picker that are ignored, and they can stall for about 15 s and cause a 502. Its search and toolbar work. Ask for it with `ask_user "Please pick <file> in the picker, then tap Done" wait=80`. If the task needs a particular kind of file, name it in the message (e.g. "a plain photo, not a Motion Photo", or the file name the user gave you) (the notification's Done works there; the card is hidden on that screen), then check the app's screen and continue.
- `type_text` with `submit=true` (IME Enter) can hang about 30 s in some fields (seen in the file picker search). Type without submit, then tap the result or the app's button.
- **Needing the user.** Whenever you are blocked on something only the person can do (lock screen, sign-in, a picker, a permission prompt, a physical check), use `ask_user` with one short, specific instruction instead of only writing in chat: they may not be looking at the chat. Keep waiting with `ask_user wait=...` calls; after about 5 minutes without Done, stop and report.
- Never type passwords, PINs, or sign in to accounts for the user; never send messages or make purchases.
- Agents can't install an APK: Android always asks the user to tap Install. Give them the APK and ask them to install it.
- Phone Pilot keeps the screen on for 3 minutes after each command (removed automatically in the file picker). It can't stop the power button.

## 8. Report

For each test, say what you did, what the screen showed (quote on-screen text, attach or describe screenshots), the time per step when asked, and anything that failed, with the exact error. Distinguish app bugs from Phone Pilot or connection problems. Save long logs to a file and send it.

