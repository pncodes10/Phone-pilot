package dev.phonepilot;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
                    "Read the current screen: foreground app, screen size and a numbered list of visible "
                    + "elements (text, description, id, centre coordinates, flags like clickable/editable). "
                    + "Call this before acting and after each action to see what changed. Element numbers "
                    + "are only valid until the next get_screen.",
                    props()));
            t.put(tool("screenshot",
                    "Take a screenshot (JPEG, scaled down). Use it to check visual layout, images, colours, "
                    + "or apps that expose no elements (games, canvases). Coordinates in the image are scaled; "
                    + "the result tells you the factor to convert back to real screen pixels.",
                    props()));
            t.put(tool("tap_element", "Tap an element by its number from the latest get_screen.",
                    props("index", "integer", "Element number from get_screen"), "index"));
            t.put(tool("tap", "Tap at real screen pixel coordinates.",
                    props("x", "integer", "x in screen pixels", "y", "integer", "y in screen pixels"), "x", "y"));
            t.put(tool("long_press", "Long-press at real screen pixel coordinates.",
                    props("x", "integer", "x", "y", "integer", "y",
                          "duration_ms", "integer", "Hold time, default 800"), "x", "y"));
            t.put(tool("swipe", "Swipe from (x1,y1) to (x2,y2) in real screen pixels.",
                    props("x1", "integer", "start x", "y1", "integer", "start y",
                          "x2", "integer", "end x", "y2", "integer", "end y",
                          "duration_ms", "integer", "Duration, default 300"), "x1", "y1", "x2", "y2"));
            t.put(tool("scroll", "Scroll the screen. 'down' reveals content further down the page.",
                    enumProp("direction", "up", "down", "left", "right"), "direction"));
            t.put(tool("type_text",
                    "Type into a text field. Pass index to choose the field, otherwise the focused field is "
                    + "used. Replaces the field's text unless append is true. submit presses Enter/Go/Search.",
                    props("text", "string", "Text to type", "index", "integer", "Field element number (optional)",
                          "submit", "boolean", "Press Enter afterwards", "append", "boolean", "Keep existing text"),
                    "text"));
            t.put(tool("press_button", "Press a system button.",
                    enumProp("button", "back", "home", "recents", "notifications", "quick_settings", "lock_screen"),
                    "button"));
            t.put(tool("list_apps", "List installed apps (name and package). Optional filter text.",
                    props("filter", "string", "Part of the app name or package")));
            t.put(tool("launch_app", "Open an app by its name (e.g. \"Settings\") or package name.",
                    props("app", "string", "App name or package"), "app"));
            t.put(tool("open_url", "Open a web link or deep link on the phone.",
                    props("url", "string", "URL or deep link"), "url"));
            t.put(tool("wait", "Wait for loading or animations to finish (max 10 seconds).",
                    props("seconds", "number", "Seconds to wait"), "seconds"));
            return t;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
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
        Result(String text, byte[] jpeg, boolean isError) { this.text = text; this.jpeg = jpeg; this.isError = isError; }

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
        try {
            switch (name) {
                case "get_screen":
                    return ok(svc.readScreen().text);
                case "screenshot": {
                    PilotService.Shot s = svc.screenshot(1280);
                    if (s.error != null) return err(s.error);
                    float f = (float) s.screenWidth / s.width;
                    return new Result(String.format(java.util.Locale.US,
                            "Screenshot %dx%d of a %dx%d screen. Multiply image coordinates by %.3f to get "
                            + "screen pixels for tap/swipe.", s.width, s.height, s.screenWidth, s.screenHeight, f),
                            s.jpeg, false);
                }
                case "tap_element":
                    return settle(svc.tapElement(in.getInt("index")));
                case "tap":
                    return settle(svc.tap(in.getInt("x"), in.getInt("y")));
                case "long_press":
                    return settle(svc.longPress(in.getInt("x"), in.getInt("y"), in.optInt("duration_ms", 800)));
                case "swipe":
                    return settle(svc.swipe(in.getInt("x1"), in.getInt("y1"), in.getInt("x2"), in.getInt("y2"),
                            in.optInt("duration_ms", 300)));
                case "scroll":
                    return settle(svc.scroll(in.getString("direction")));
                case "type_text":
                    return settle(svc.typeText(in.getString("text"), in.optInt("index", -1),
                            in.optBoolean("submit", false), in.optBoolean("append", false)));
                case "press_button":
                    return settle(svc.pressButton(in.getString("button")));
                case "list_apps":
                    return ok(svc.listApps(in.optString("filter", "")));
                case "launch_app": {
                    String r = svc.launchApp(in.getString("app"));
                    svc.waitForIdle(400, 500, 4000);
                    return ok(r);
                }
                case "open_url": {
                    String r = svc.openUrl(in.getString("url"));
                    svc.waitForIdle(400, 500, 4000);
                    return ok(r);
                }
                case "batch": {
                    // Several actions in one round trip; stops at the first error. "screen": true appends get_screen.
                    org.json.JSONArray steps = in.optJSONArray("steps");
                    StringBuilder log = new StringBuilder();
                    byte[] img = null;
                    boolean failed = false;
                    for (int i = 0; steps != null && i < steps.length(); i++) {
                        JSONObject st = steps.getJSONObject(i);
                        String n = st.getString("name");
                        if ("batch".equals(n)) continue;
                        JSONObject a = st.optJSONObject("input");
                        Result r = run(n, a == null ? new JSONObject() : a);
                        log.append(i + 1).append(". ").append(n).append(": ").append(r.text).append("\n");
                        if (r.jpeg != null) img = r.jpeg;
                        if (r.isError) { failed = true; break; }
                    }
                    if (!failed && in.optBoolean("screen", false)) log.append("\n").append(svc.readScreen().text);
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

    /** Give the UI a moment to react after an action. */
    private Result settle(String msg) {
        long ms = svc.waitForIdle(300, 300, 2500);
        return ok(msg + " (UI settled in " + ms + " ms)");
    }

    private static Result ok(String s) { return new Result(s, null, false); }
    private static Result err(String s) { return new Result(s, null, true); }
}
