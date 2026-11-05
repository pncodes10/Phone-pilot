package dev.phonepilot;

import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The tool set Claude uses to drive the phone. The same definitions are used by the
 * on-phone agent (sent to the Claude API) and by the Wi-Fi bridge (served to an MCP
 * server on your computer), so both modes behave identically.
 */
public final class DeviceTools {

    private final PilotService svc;

    DeviceTools(PilotService svc) { this.svc = svc; }

    // ------------------------------------------------------------------ schemas

    public static JSONArray definitions() {
        try {
            JSONArray t = new JSONArray();
            t.put(tool("get_screen",
                    "Read the current screen as a short numbered list, one item per line, e.g. "
                    + "[5] Button \"Save\" (id=save_btn) @540,1820 [click]. Use the numbers with tap_id and "
                    + "type_text; they are only valid until the next screen read (every action returns a fresh "
                    + "screen, so you rarely need this). When the app exposes almost no items (games, drawing, "
                    + "web views) a labelled screenshot with numbered boxes and grid cells is attached.",
                    props("full", "boolean", "Old detailed listing of every element, for debugging",
                          "labels", "string", "Labelled screenshot: auto (default, only when few items), true, false")));
            t.put(tool("screenshot",
                    "Take a screenshot (JPEG, scaled down). Use it to check visual layout, images and colours. "
                    + "labels=true draws the numbered boxes from a fresh screen read. Coordinates in the image are "
                    + "scaled; the result gives the factor to convert back to real screen pixels.",
                    props("labels", "boolean", "Draw numbered boxes (renumbers the screen)")));
            t.put(tool("tap_id",
                    "Tap the item with this number from the latest screen. If the screen changed since the numbers "
                    + "were given out, nothing is tapped and an error with the new screen comes back.",
                    observe(props("id", "integer", "Item number, e.g. 5 for [5]")), "id"));
            t.put(tool("tap_text",
                    "Tap the visible item whose text, description or hint matches (case-insensitive; exact "
                    + "matches and clickable items win). Pass res_id to match a resource id instead or as well.",
                    observe(props("text", "string", "Text to match (contains, unless exact)",
                                  "res_id", "string", "Resource id, e.g. save_btn",
                                  "exact", "boolean", "Whole text must match"))));
            t.put(tool("tap_element", "Old name for tap_id.",
                    observe(props("index", "integer", "Item number")), "index"));
            t.put(tool("tap", "Tap at real screen pixel coordinates.",
                    observe(props("x", "integer", "x in screen pixels", "y", "integer", "y in screen pixels")), "x", "y"));
            t.put(tool("long_press", "Long-press at real screen pixel coordinates.",
                    observe(props("x", "integer", "x", "y", "integer", "y",
                          "duration_ms", "integer", "Hold time, default 800")), "x", "y"));
            t.put(tool("swipe", "Swipe from (x1,y1) to (x2,y2) in real screen pixels.",
                    observe(props("x1", "integer", "start x", "y1", "integer", "start y",
                          "x2", "integer", "end x", "y2", "integer", "end y",
                          "duration_ms", "integer", "Duration, default 300")), "x1", "y1", "x2", "y2"));
            t.put(tool("scroll", "Scroll the screen. 'down' reveals content further down the page.",
                    observe(enumProp("direction", "up", "down", "left", "right")), "direction"));
            t.put(tool("type_text",
                    "Type into a text field. Pass id (item number) to choose the field, otherwise the focused "
                    + "field is used. Replaces the field's text unless append is true. submit presses Enter/Go/Search.",
                    observe(props("text", "string", "Text to type", "id", "integer", "Field item number (optional)",
                          "submit", "boolean", "Press Enter afterwards", "append", "boolean", "Keep existing text")),
                    "text"));
            t.put(tool("press_button", "Press a system button.",
                    observe(enumProp("button", "back", "home", "recents", "notifications", "quick_settings", "lock_screen")),
                    "button"));
            t.put(tool("list_apps", "List installed apps (name and package). Optional filter text.",
                    props("filter", "string", "Part of the app name or package")));
            t.put(tool("launch_app", "Open an app by its name (e.g. \"Settings\") or package name.",
                    observe(props("app", "string", "App name or package")), "app"));
            t.put(tool("open_url", "Open a web link or deep link on the phone.",
                    observe(props("url", "string", "URL or deep link")), "url"));
            t.put(tool("wait_for",
                    "Wait until an item with this text (or res_id) is on screen, checking every 300 ms. Reports "
                    + "found or timed out and how long it took. Use it instead of fixed waits.",
                    observe(props("text", "string", "Text to wait for (contains, unless exact)",
                                  "res_id", "string", "Resource id to wait for",
                                  "exact", "boolean", "Whole text must match",
                                  "timeout", "number", "Seconds, default 5, max 60"))));
            t.put(tool("wait_gone",
                    "Wait until no item with this text (or res_id) is on screen, e.g. a \"Loading\" message. "
                    + "Checks every 300 ms; reports gone or timed out and how long it took.",
                    observe(props("text", "string", "Text that should disappear",
                                  "res_id", "string", "Resource id that should disappear",
                                  "exact", "boolean", "Whole text must match",
                                  "timeout", "number", "Seconds, default 5, max 60"))));
            t.put(tool("get_text",
                    "Full, uncut text of one item (the screen list shortens long text), e.g. a log. Pass id "
                    + "(item number), or text / res_id to find it.",
                    props("id", "integer", "Item number", "text", "string", "Part of its text",
                          "res_id", "string", "Resource id")));
            t.put(tool("wait", "Fixed pause (max 10 seconds). Prefer wait_for or wait_gone.",
                    props("seconds", "number", "Seconds to wait"), "seconds"));
            return t;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /** Adds the observe flag that every action takes. */
    private static JSONObject observe(JSONObject p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "boolean");
        o.put("description", "Return the new screen after the action (default true)");
        p.put("observe", o);
        return p;
    }

    private static JSONObject tool(String name, String desc, JSONObject properties, String... required)
            throws JSONException {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        schema.put("required", req);
        JSONObject t = new JSONObject();
        t.put("name", name);
        t.put("description", desc);
        t.put("input_schema", schema);
        return t;
    }

    /** props("name","type","description", ...) */
    private static JSONObject props(String... triples) throws JSONException {
        JSONObject p = new JSONObject();
        for (int i = 0; i + 2 < triples.length; i += 3) {
            JSONObject o = new JSONObject();
            o.put("type", triples[i + 1]);
            o.put("description", triples[i + 2]);
            p.put(triples[i], o);
        }
        return p;
    }

    private static JSONObject enumProp(String name, String... values) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "string");
        JSONArray e = new JSONArray();
        for (String v : values) e.put(v);
        o.put("enum", e);
        JSONObject p = new JSONObject();
        p.put(name, o);
        return p;
    }

    // ------------------------------------------------------------------ execution

    /** A tool result: text plus an optional JPEG image. */
    public static final class Result {
        public final String text;
        public final byte[] jpeg;
        public final boolean isError;
        public Result(String text, byte[] jpeg, boolean isError) { this.text = text; this.jpeg = jpeg; this.isError = isError; }

        /** Content blocks in Claude API tool_result format. */
        public JSONArray toContent() throws JSONException {
            JSONArray c = new JSONArray();
            if (jpeg != null) {
                JSONObject src = new JSONObject();
                src.put("type", "base64");
                src.put("media_type", "image/jpeg");
                src.put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP));
                JSONObject img = new JSONObject();
                img.put("type", "image");
                img.put("source", src);
                c.put(img);
            }
            JSONObject t = new JSONObject();
            t.put("type", "text");
            t.put("text", text);
            c.put(t);
            return c;
        }
    }

    /** Run one tool. Serialised so the agent and the Wi-Fi bridge never act at the same time. */
    public synchronized Result run(String name, JSONObject in) {
        actionStart = SystemClock.uptimeMillis();
        try {
            switch (name) {
                case "get_screen": {
                    Observed v = view(in.optBoolean("full", false), labelsMode(in.opt("labels"), "auto"));
                    return new Result(v.text, v.jpeg, false);
                }
                case "screenshot": {
                    if (labelsMode(in.opt("labels"), "false").equals("true")) {
                        Observed v = view(false, "true");
                        return new Result(v.text, v.jpeg, v.jpeg == null);
                    }
                    PilotService.Shot s = svc.screenshot(1280);
                    if (s.error != null) return err(s.error);
                    return new Result(shotNote(s), s.jpeg, false);
                }
                case "tap_id":
                    return act(in, svc.tapId(in.getInt("id")), true);
                case "tap_element":
                    return act(in, svc.tapId(in.has("index") ? in.getInt("index") : in.getInt("id")), true);
                case "tap_text":
                    return act(in, svc.tapText(in.optString("text", ""), in.optString("res_id", ""),
                            in.optBoolean("exact", false)), true);
                case "tap":
                    return act(in, outcome(svc.tap(in.getInt("x"), in.getInt("y")), "Tapped"), true);
                case "long_press":
                    return act(in, outcome(svc.longPress(in.getInt("x"), in.getInt("y"),
                            in.optInt("duration_ms", 800)), "Long-pressed"), true);
                case "swipe":
                    return act(in, outcome(svc.swipe(in.getInt("x1"), in.getInt("y1"), in.getInt("x2"), in.getInt("y2"),
                            in.optInt("duration_ms", 300)), "Swiped"), true);
                case "scroll":
                    return act(in, outcome(svc.scroll(in.getString("direction")), "Swiped"), true);
                case "type_text":
                    return act(in, svc.typeText(in.getString("text"), in.optInt("id", in.optInt("index", -1)),
                            in.optBoolean("submit", false), in.optBoolean("append", false)), true);
                case "press_button":
                    return act(in, outcome(svc.pressButton(in.getString("button")), "Pressed"), true);
                case "list_apps":
                    return ok(svc.listApps(in.optString("filter", "")));
                case "launch_app": {
                    PilotService.Outcome o = outcome(svc.launchApp(in.getString("app")), "Launched");
                    if (o.ok) {
                        String pkg = o.msg.substring("Launched ".length()).replaceAll("\\.$", "");
                        long t0 = SystemClock.uptimeMillis();
                        while (SystemClock.uptimeMillis() - t0 < 8000) {
                            if (pkg.equals(svc.activePackage())) break;
                            PilotService.sleep(150);
                        }
                        svc.waitForIdle(300, 500, 4000);
                        o = PilotService.Outcome.ok(o.msg + " In front after " + (SystemClock.uptimeMillis() - t0) + " ms.");
                    }
                    return act(in, o, false);
                }
                case "open_url": {
                    PilotService.Outcome o = outcome(svc.openUrl(in.getString("url")), "Opened");
                    if (o.ok) svc.waitForIdle(400, 500, 4000);
                    return act(in, o, false);
                }
                case "get_text":
                    return getText(in);
                case "wait_for":
                    return waitFor(in, false);
                case "wait_gone":
                    return waitFor(in, true);
                case "batch": {
                    // Several actions in one round trip; stops at the first error. The new screen is
                    // returned once at the end (observe=false or the old screen=false turns that off).
                    JSONArray steps = in.optJSONArray("steps");
                    StringBuilder log = new StringBuilder();
                    byte[] img = null;
                    boolean failed = false;
                    for (int i = 0; steps != null && i < steps.length(); i++) {
                        JSONObject st = steps.getJSONObject(i);
                        String n = st.getString("name");
                        if ("batch".equals(n)) continue;
                        JSONObject a = st.optJSONObject("input");
                        a = a == null ? new JSONObject() : new JSONObject(a.toString());
                        a.put("observe", false);
                        Result r = run(n, a);
                        log.append(i + 1).append(". ").append(n).append(": ").append(r.text).append("\n");
                        if (r.jpeg != null) img = r.jpeg;
                        if (r.isError) { failed = true; break; }
                    }
                    if (in.optBoolean("observe", in.optBoolean("screen", true))) {
                        Observed v = view(false, "auto");
                        log.insert(0, failed ? "error:\n" : "ok:\n").append("\nscreen:\n").append(v.text);
                        if (v.jpeg != null) img = v.jpeg;
                    }
                    return new Result(log.toString(), img, failed);
                }
                case "wait": {
                    double s = Math.min(10, Math.max(0, in.optDouble("seconds", 1)));
                    PilotService.sleep((long) (s * 1000));
                    return ok("Waited " + s + "s.");
                }
                default:
                    return err("Unknown tool " + name);
            }
        } catch (Exception e) {
            return err(name + " failed: " + e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private long actionStart;

    /** A trimmed (or full) screen, plus a labelled screenshot when asked or when the screen is unreadable. */
    private static final class Observed { String text; byte[] jpeg; }

    private Observed view(boolean full, String labels) {
        PilotService.Screen s = svc.readScreen(full);
        for (int i = 0; i < 3 && s.appItems < PilotService.SPARSE_LIMIT && !labels.equals("false"); i++) {
            // Often just a screen that hasn't drawn yet; look again before falling back to a screenshot.
            PilotService.sleep(400);
            s = svc.readScreen(full);
        }
        Observed v = new Observed();
        StringBuilder sb = new StringBuilder(s.text);
        boolean sparse = s.appItems < PilotService.SPARSE_LIMIT;
        boolean draw = labels.equals("true") || (labels.equals("auto") && sparse);
        if (draw) {
            List<PilotService.Elem> boxes = new ArrayList<>(s.items);
            if (sparse) {
                List<PilotService.Elem> cells = svc.addGridCells(6);
                if (!cells.isEmpty()) {
                    PilotService.Elem a = cells.get(0), z = cells.get(cells.size() - 1);
                    sb.append("Few readable items here (game, drawing or web view?). [").append(a.num).append("]-[")
                      .append(z.num).append("] are grid cells of about ").append(a.b.width()).append('x')
                      .append(a.b.height()).append(" px, numbered left to right, top to bottom on the labelled "
                      + "screenshot; tap_id on a cell taps its centre. For exact spots use tap x y.\n");
                }
                boxes.addAll(cells);
            }
            PilotService.Shot shot = svc.screenshot(1280, boxes);
            if (shot.error != null) {
                sb.append("(labelled screenshot failed: ").append(shot.error).append(")\n");
            } else {
                v.jpeg = shot.jpeg;
                sb.append("Labelled screenshot attached. ").append(shotNote(shot)).append('\n');
            }
        }
        v.text = sb.toString();
        return v;
    }

    private static String shotNote(PilotService.Shot s) {
        float f = (float) s.screenWidth / s.width;
        return String.format(java.util.Locale.US,
                "Screenshot %dx%d of a %dx%d screen. Multiply image coordinates by %.3f to get "
                + "screen pixels for tap/swipe.", s.width, s.height, s.screenWidth, s.screenHeight, f);
    }

    private static String labelsMode(Object o, String dflt) {
        if (o == null || JSONObject.NULL.equals(o)) return dflt;
        String v = o.toString().trim().toLowerCase(java.util.Locale.US);
        if (v.equals("true") || v.equals("yes") || v.equals("1")) return "true";
        if (v.equals("false") || v.equals("no") || v.equals("0")) return "false";
        return "auto";
    }

    /**
     * Finish an action: let the UI settle, then (unless observe=false) attach the new trimmed screen,
     * so the caller sees the result without another round trip. Format:
     *   ok: <message>\n\nscreen:\n<trimmed layout>      (or "error: ..." when it failed)
     */
    private Result act(JSONObject in, PilotService.Outcome o, boolean settle) {
        String msg = o.msg;
        if (o.ok && settle) msg += " (UI settled in " + svc.settleAfter(actionStart) + " ms)";
        if (!in.optBoolean("observe", true)) return new Result(msg, null, !o.ok);
        Observed v = view(false, "auto");
        return new Result((o.ok ? "ok: " : "error: ") + msg + "\n\nscreen:\n" + v.text, v.jpeg, !o.ok);
    }

    private static PilotService.Outcome outcome(String msg, String okPrefix) {
        return msg.startsWith(okPrefix) ? PilotService.Outcome.ok(msg) : PilotService.Outcome.fail(msg);
    }

    private Result getText(JSONObject in) throws JSONException {
        android.view.accessibility.AccessibilityNodeInfo n;
        if (in.has("id")) {
            StringBuilder why = new StringBuilder();
            PilotService.Elem e = svc.resolve(in.getInt("id"), why);
            if (e == null) return err(why.toString());
            if (e.node == null) return err("[" + in.getInt("id") + "] is a grid cell; it has no text.");
            n = e.node;
        } else {
            List<android.view.accessibility.AccessibilityNodeInfo> f =
                    svc.findNodes(in.optString("text", ""), in.optString("res_id", ""), false);
            if (f.isEmpty()) return err("Nothing matching that is on screen.");
            n = f.get(0);
        }
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        String text = t == null ? "" : t.toString();
        if (text.length() > 200000) text = text.substring(0, 200000) + "\n[cut at 200000 characters]";
        return ok(text.length() + " characters:\n" + text);
    }

    /** wait_for / wait_gone: poll every 300 ms until the text is there (or gone) or the time runs out. */
    private Result waitFor(JSONObject in, boolean gone) {
        String text = in.optString("text", "").trim();
        String rid = in.optString("res_id", "").trim();
        if (text.isEmpty() && rid.isEmpty()) return err("Pass text or res_id.");
        boolean exact = in.optBoolean("exact", false);
        double secs = Math.min(60, Math.max(0, in.optDouble("timeout", 5)));
        String what = (text.isEmpty() ? "" : "\"" + text + "\"") + (rid.isEmpty() ? "" : " (id=" + rid + ")");
        what = what.trim();
        long start = SystemClock.uptimeMillis();
        long deadline = start + (long) (secs * 1000);
        boolean hit;
        long now;
        while (true) {
            boolean present = !svc.findNodes(text, rid, exact).isEmpty();
            if (gone && !present) {
                // Screens are briefly empty during transitions; make sure it is really gone.
                PilotService.sleep(150);
                present = !svc.findNodes(text, rid, exact).isEmpty();
            }
            hit = gone != present;
            now = SystemClock.uptimeMillis();
            if (hit || now >= deadline) break;
            PilotService.sleep(Math.min(300, deadline - now));
        }
        long took = now - start;
        String msg = hit
                ? (gone ? what + " is gone after " : "Found " + what + " after ") + took + " ms."
                : "Timed out after " + took + " ms: " + what + (gone ? " is still on screen." : " did not appear.");
        return act(in, hit ? PilotService.Outcome.ok(msg) : PilotService.Outcome.fail(msg), false);
    }

    private static Result ok(String s) { return new Result(s, null, false); }
    private static Result err(String s) { return new Result(s, null, true); }
}
