package dev.phonepilot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The accessibility service is the part that actually controls the phone.
 * It can read every on-screen element, tap/swipe with gestures, type into fields,
 * press Back/Home, take screenshots (Android 11+), and launch apps.
 *
 * Every method here is blocking and must be called from a background thread.
 */
public class PilotService extends AccessibilityService {

    private static volatile PilotService instance;

    public static PilotService get() { return instance; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();

    private View overlay;
    private TextView overlayText;

    final DeviceTools tools = new DeviceTools(this);
    final AgentRunner agent = new AgentRunner(this);
    final RemoteServer remote = new RemoteServer(this);
    final RelayClient relay = new RelayClient(this);

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.add("Accessibility service connected.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Remember when the UI last changed, so actions can wait just until it settles.
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !getPackageName().contentEquals(pkg)) lastUiEvent = android.os.SystemClock.uptimeMillis();
        if (pkg != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !getPackageName().contentEquals(pkg)) {
            String p = pkg.toString();
            if (!p.equals(fgPkg)) { fgPkg = p; main.post(syncAwakeView); }
        }
    }

    volatile long lastUiEvent = 0;

    private android.os.PowerManager.WakeLock awake;
    private View awakeView;           // invisible 1x1 window with FLAG_KEEP_SCREEN_ON
    private volatile long awakeUntil = 0;
    private volatile String fgPkg = "";

    /** System screens that ignore taps while any other app draws a window on top (tapjacking protection). */
    private static boolean sensitive(String pkg) {
        return pkg.contains("documentsui") || pkg.contains("permissioncontroller")
                || pkg.contains("packageinstaller") || pkg.contains("photopicker") || pkg.contains("mediaprovider");
    }

    /** Keep the screen on for 3 minutes after each remote command, without the visible bar. */
    @SuppressWarnings("deprecation")
    void keepAwake() {
        awakeUntil = android.os.SystemClock.uptimeMillis() + 3 * 60 * 1000L;
        try {
            synchronized (this) {
                if (awake == null) {
                    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    awake = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "phonepilot:remote");
                    awake.setReferenceCounted(false);
                }
                awake.acquire(3 * 60 * 1000L);
            }
        } catch (Exception e) { Log.add("Could not take a wake lock: " + e); }
        main.post(syncAwakeView);
        main.removeCallbacks(syncAwakeView);
        main.postDelayed(syncAwakeView, 3 * 60 * 1000L + 500);
    }

    /** Adds or removes the keep-awake window to match the current state. Runs on the main thread. */
    private final Runnable syncAwakeView = new Runnable() {
        @Override public void run() {
            boolean want = android.os.SystemClock.uptimeMillis() < awakeUntil && !sensitive(fgPkg);
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (want && awakeView == null) {
                View v = new View(PilotService.this);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSPARENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                try { wm.addView(v, lp); awakeView = v; } catch (Exception e) { Log.add("Keep-awake window failed: " + e); }
            } else if (!want && awakeView != null) {
                try { wm.removeView(awakeView); } catch (Exception ignored) { }
                awakeView = null;
            }
        }
    };

    /** Null when the screen is usable, otherwise a short reason. */
    String screenProblem() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isInteractive()) return "The phone's screen is off.";
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km.isKeyguardLocked()) return "The phone is on the lock screen.";
        return null;
    }

    /** Wait until the UI has been quiet for quietMs (at least minMs, at most maxMs). Returns ms waited. */
    long waitForIdle(long minMs, long quietMs, long maxMs) {
        long start = android.os.SystemClock.uptimeMillis();
        sleep(minMs);
        while (true) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - start >= maxMs || now - lastUiEvent >= quietMs) return now - start;
            sleep(50);
        }
    }

    /**
     * Settle after an action that started at actionStart (uptime ms): wait for the screen to start
     * changing (up to 1 s; some transitions begin 300-600 ms after the tap or key), then until it has
     * been quiet for 300 ms. Returns ms waited since actionStart.
     */
    long settleAfter(long actionStart) {
        long now;
        while (true) {
            now = android.os.SystemClock.uptimeMillis();
            if (lastUiEvent > actionStart || now - actionStart >= 1000) break;
            sleep(50);
        }
        while (true) {
            now = android.os.SystemClock.uptimeMillis();
            if (now - actionStart >= 3000 || (now - lastUiEvent >= 300 && now - actionStart >= 300)) break;
            sleep(50);
        }
        return now - actionStart;
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        agent.stop();
        remote.stop();
        relay.stop();
        hideOverlay();
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ screen reading

    /** Full screen size including the navigation bar (the app-area metrics leave it out). */
    @SuppressWarnings("deprecation")
    DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) return dm;
        } catch (Exception ignored) { }
        return getResources().getDisplayMetrics();
    }

    /** Package of the active window, or "" while switching. */
    String activePackage() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (!w.isActive()) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** One numbered item from the latest screen read. Grid cells have no node, only a position. */
    static final class Elem {
        int num;
        AccessibilityNodeInfo node;     // null for screenshot grid cells
        String pkg = "", cls = "View", text = "", desc = "", hint = "", resId = "", label = "";
        final Rect b = new Rect();
        boolean click, longClick, edit, scroll, checkable, checked, focused, enabled = true, password, selected;
        boolean cell;
        boolean childLabel;             // label was taken from the text inside it
    }

    public static final class Screen {
        public String text;
        public String packageName;
        public int seq;
        /** Numbered items that belong to the foreground app (grid cells not counted). */
        public int appItems;
        /** Snapshot of the numbered items, for drawing labelled screenshots. */
        List<Elem> items = new ArrayList<>();
    }

    /** Number -> item, rebuilt on every screen read. tap_id and type_text look items up here. */
    private final Map<Integer, Elem> numbered = new LinkedHashMap<>();
    private int screenSeq = 0;

    /** Fewer app items than this and the screen counts as unreadable (games, canvases, web views). */
    static final int SPARSE_LIMIT = 2;

    /**
     * Reads every visible window (skipping our own overlay) and numbers the useful items.
     * full=false: hidden, tiny, off-screen and empty layout items are dropped, one short line each.
     * full=true: the older detailed listing of everything with text or an action, for debugging.
     */
    public synchronized Screen readScreen(boolean full) {
        numbered.clear();
        screenSeq++;
        DisplayMetrics dm = realMetrics();
        Rect screenRect = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        int minPx = Math.max(4, Math.round(4 * dm.density));

        String activePkg = "";
        int keyboardTop = -1;
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        List<AccessibilityWindowInfo> sorted = new ArrayList<>(getWindows());
        // Draw order: higher layer is on top. List the top-most windows first.
        Collections.sort(sorted, new Comparator<AccessibilityWindowInfo>() {
            @Override public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return b.getLayer() - a.getLayer();
            }
        });
        for (AccessibilityWindowInfo w : sorted) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null) continue;
            String pkg = r.getPackageName() == null ? "" : r.getPackageName().toString();
            if (w.isActive() && !pkg.isEmpty()) activePkg = pkg;
            if (!full && w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                // The keyboard's keys are noise for app testing: one line instead of dozens.
                Rect wb = new Rect();
                w.getBoundsInScreen(wb);
                keyboardTop = wb.top;
                continue;
            }
            if (!full && pkg.equals("com.android.systemui")) {
                // Status and navigation bars are noise for app testing; keep the shade and system dialogs.
                Rect wb = new Rect();
                w.getBoundsInScreen(wb);
                if (wb.height() < dm.heightPixels * 0.15f) continue;
            }
            roots.add(r);
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) {
                roots.add(r);
                if (r.getPackageName() != null) activePkg = r.getPackageName().toString();
            }
        }

        List<Elem> out = new ArrayList<>();
        Set<AccessibilityNodeInfo> consumed = new HashSet<>();
        for (AccessibilityNodeInfo r : roots) {
            String pkg = r.getPackageName() == null ? "" : r.getPackageName().toString();
            walk(r, 0, out, full, screenRect, minPx, consumed, pkg, "");
        }

        Screen s = new Screen();
        s.packageName = activePkg;
        s.seq = screenSeq;
        StringBuilder sb = new StringBuilder();
        if (full) {
            sb.append("Foreground app: ").append(activePkg)
              .append("\nScreen #").append(screenSeq).append(", size ").append(dm.widthPixels).append('x')
              .append(dm.heightPixels).append(" px (tap/swipe coordinates use these pixels)\nElements:\n");
        } else {
            sb.append("Screen #").append(screenSeq).append("  app=").append(activePkg)
              .append("  size=").append(dm.widthPixels).append('x').append(dm.heightPixels).append('\n');
            if (keyboardTop >= 0) sb.append("Keyboard open from y=").append(keyboardTop)
                    .append(" (type_text doesn't need it; press back to hide it)\n");
        }
        int n = 1;
        for (Elem e : out) {
            e.num = n++;
            numbered.put(e.num, e);
            sb.append(full ? fullLine(e) : shortLine(e)).append('\n');
            if (e.pkg.equals(activePkg)) s.appItems++;
        }
        if (out.isEmpty()) sb.append("(no readable items: the app may draw its own UI)\n");
        s.items.addAll(out);
        s.text = sb.toString();
        return s;
    }

    /** Back-compat: the trimmed layout. */
    public Screen readScreen() { return readScreen(false); }

    private void walk(AccessibilityNodeInfo n, int depth, List<Elem> out, boolean full, Rect screen,
                      int minPx, Set<AccessibilityNodeInfo> consumed, String pkg, String parentLabel) {
        if (n == null || depth > 60) return;
        if (!n.isVisibleToUser()) return;
        Elem e = new Elem();
        e.node = n;
        e.pkg = pkg;
        n.getBoundsInScreen(e.b);
        e.text = str(n.getText());
        e.desc = str(n.getContentDescription());
        e.hint = str(n.getHintText());
        String id = n.getViewIdResourceName();
        e.resId = id == null ? "" : id.substring(id.indexOf('/') + 1);
        String cls = n.getClassName() == null ? "View" : n.getClassName().toString();
        e.cls = cls.substring(cls.lastIndexOf('.') + 1);
        e.click = n.isClickable();
        e.longClick = n.isLongClickable();
        e.edit = n.isEditable();
        e.scroll = n.isScrollable();
        e.checkable = n.isCheckable();
        e.checked = n.isChecked();
        e.focused = n.isFocused();
        e.enabled = n.isEnabled();
        e.password = n.isPassword();
        e.selected = n.isSelected();
        if (e.edit && n.isShowingHintText()) e.text = "";

        boolean tappable = e.click || e.longClick || e.checkable;
        boolean kept = false;
        boolean actionable = tappable || e.edit || e.scroll;
        boolean hasText = !e.text.isEmpty() || !e.desc.isEmpty();

        if (full) {
            if (actionable || hasText) out.add(e);
        } else {
            boolean tiny = e.b.width() < minPx || e.b.height() < minPx;
            boolean offScreen = !Rect.intersects(e.b, screen);
            if (!tiny && !offScreen) {
                e.label = !e.text.isEmpty() ? e.text : e.desc;
                if (tappable && e.label.isEmpty()) {
                    // A clickable row or card: name it after the text inside it, and don't list that text again.
                    e.label = descendantText(n, 6, 3, consumed);
                    e.childLabel = !e.label.isEmpty();
                }
                if (actionable) {
                    // Drop empty layout items: clickable containers with nothing to identify them by.
                    if (!e.label.isEmpty() || e.edit || e.scroll || e.checkable || !e.resId.isEmpty()) { out.add(e); kept = true; }
                } else if (hasText && !consumed.contains(n)
                        && !(parentLabel.equals(e.text.isEmpty() ? e.desc : e.text))) {
                    out.add(e);
                    kept = true;
                }
            }
        }
        String passLabel = kept && !e.label.isEmpty() ? e.label : parentLabel;
        for (int i = 0; i < n.getChildCount(); i++)
            walk(n.getChild(i), depth + 1, out, full, screen, minPx, consumed, pkg, passLabel);
    }

    /** Text of up to max visible descendants, joined, for naming a clickable container. */
    private static String descendantText(AccessibilityNodeInfo n, int depthLeft, int max,
                                         Set<AccessibilityNodeInfo> consumed) {
        List<String> parts = new ArrayList<>();
        collectText(n, depthLeft, max, parts, consumed);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) { if (sb.length() > 0) sb.append(" | "); sb.append(p); }
        return sb.toString();
    }

    private static void collectText(AccessibilityNodeInfo n, int depthLeft, int max, List<String> parts,
                                    Set<AccessibilityNodeInfo> consumed) {
        for (int i = 0; i < n.getChildCount() && parts.size() < max; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null || !c.isVisibleToUser()) continue;
            String t = str(c.getText());
            if (t.isEmpty()) t = str(c.getContentDescription());
            boolean ownAction = c.isClickable() || c.isEditable() || c.isCheckable();
            if (ownAction) continue;   // listed on its own line
            if (!t.isEmpty()) { parts.add(t); consumed.add(c); }
            else if (depthLeft > 0) collectText(c, depthLeft - 1, max, parts, consumed);
        }
    }

    /** [5] Button "Save" (id=save_btn) @540,1820 [click] */
    private static String shortLine(Elem e) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(e.num).append("] ").append(e.cls);
        if (e.edit) {
            sb.append(" \"").append(e.password && !e.text.isEmpty() ? "••••" : clip(e.text)).append('"');
            if (!e.hint.isEmpty()) sb.append(" hint=\"").append(clip(e.hint)).append('"');
        } else if (!e.label.isEmpty()) {
            sb.append(" \"").append(clip(e.label)).append('"');
        }
        if (!e.desc.isEmpty() && !e.desc.equals(e.label)) sb.append(" desc=\"").append(clip(e.desc)).append('"');
        if (!e.resId.isEmpty()) sb.append(" (id=").append(e.resId).append(')');
        sb.append(" @").append(e.b.centerX()).append(',').append(e.b.centerY());
        List<String> f = new ArrayList<>();
        if (e.click) f.add("click");
        if (e.longClick) f.add("long");
        if (e.edit) f.add("edit");
        if (e.scroll) f.add("scroll");
        if (e.checkable) f.add(e.checked ? "checked" : "unchecked");
        if (e.selected) f.add("selected");
        if (e.focused) f.add("focused");
        if (!e.enabled) f.add("disabled");
        if (e.password) f.add("password");
        if (!f.isEmpty()) sb.append(" [").append(join(f)).append(']');
        return sb.toString();
    }

    /** The older, detailed format (get_screen full=true). */
    private static String fullLine(Elem e) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(e.num).append("] ").append(e.cls);
        if (!e.text.isEmpty()) sb.append(" text=\"").append(clip(e.text)).append('"');
        if (!e.desc.isEmpty()) sb.append(" desc=\"").append(clip(e.desc)).append('"');
        if (!e.hint.isEmpty() && e.edit) sb.append(" hint=\"").append(clip(e.hint)).append('"');
        if (!e.resId.isEmpty()) sb.append(" id=").append(e.resId);
        sb.append(" @(").append(e.b.centerX()).append(',').append(e.b.centerY()).append(')');
        sb.append(" bounds=[").append(e.b.left).append(',').append(e.b.top).append("][")
          .append(e.b.right).append(',').append(e.b.bottom).append(']');
        if (!e.pkg.isEmpty()) sb.append(" pkg=").append(e.pkg);
        List<String> flags = new ArrayList<>();
        if (e.click) flags.add("clickable");
        if (e.longClick) flags.add("long-clickable");
        if (e.edit) flags.add("editable");
        if (e.scroll) flags.add("scrollable");
        if (e.checkable) flags.add(e.checked ? "checked" : "unchecked");
        if (e.selected) flags.add("selected");
        if (e.focused) flags.add("focused");
        if (!e.enabled) flags.add("disabled");
        if (e.password) flags.add("password");
        if (!flags.isEmpty()) sb.append(' ').append(join(flags));
        return sb.toString();
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    private static String clip(CharSequence c) {
        String s = c.toString().replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        return sb.toString();
    }

    /**
     * Adds numbered grid cells to the current numbering, so screens with no readable items can
     * still be tapped by number (tap_id on a cell taps its centre). Returns the cells added.
     */
    synchronized List<Elem> addGridCells(int cols) {
        DisplayMetrics dm = realMetrics();
        int cw = dm.widthPixels / cols;
        int rows = Math.max(1, Math.round((float) dm.heightPixels / cw));
        int ch = dm.heightPixels / rows;
        int n = numbered.size() + 1;
        List<Elem> cells = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Elem e = new Elem();
                e.cell = true;
                e.cls = "Cell";
                e.num = n++;
                e.b.set(c * cw, r * ch, c == cols - 1 ? dm.widthPixels : (c + 1) * cw,
                        r == rows - 1 ? dm.heightPixels : (r + 1) * ch);
                numbered.put(e.num, e);
                cells.add(e);
            }
        }
        return cells;
    }

    synchronized int currentSeq() { return screenSeq; }

    /**
     * Looks up a number from the latest screen read and checks the item is still there and
     * unchanged. On a problem returns null and puts the reason in why.
     */
    synchronized Elem resolve(int num, StringBuilder why) {
        Elem e = numbered.get(num);
        if (e == null) {
            why.append("No item [").append(num).append("] on screen #").append(screenSeq)
               .append(" (it has ").append(numbered.size()).append(" items). Nothing was done.");
            return null;
        }
        if (e.cell) return e;
        String changed = null;
        AccessibilityNodeInfo n = e.node;
        if (!n.refresh() || !n.isVisibleToUser()) {
            changed = "it is no longer on screen";
        } else {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            int tol = Math.max(4, Math.round(8 * getResources().getDisplayMetrics().density));
            String id = n.getViewIdResourceName();
            id = id == null ? "" : id.substring(id.indexOf('/') + 1);
            String text = str(n.getText());
            if (n.isEditable() && n.isShowingHintText()) text = "";
            if (!id.equals(e.resId)) changed = "a different item is there now";
            else if (Math.abs(b.centerX() - e.b.centerX()) > tol || Math.abs(b.centerY() - e.b.centerY()) > tol)
                changed = "it moved from " + e.b.centerX() + "," + e.b.centerY() + " to " + b.centerX() + "," + b.centerY();
            else if (!e.edit && !text.equals(e.text)) changed = "its text changed to \"" + clip(text) + "\"";
            else if (!str(n.getContentDescription()).equals(e.desc)) changed = "its description changed";
            else if (e.childLabel && !descendantText(n, 6, 3, new HashSet<AccessibilityNodeInfo>()).equals(e.label))
                changed = "its text changed";  // e.g. a recycled list row now showing another item
        }
        if (changed != null) {
            why.append("The screen has changed since screen #").append(screenSeq).append(" was read: item [")
               .append(num).append("] ").append(describe(e)).append(' ').append(changed)
               .append(". Nothing was tapped; read the screen again and use the new numbers.");
            return null;
        }
        return e;
    }

    static String describe(Elem e) {
        if (e.cell) return "cell @" + e.b.centerX() + "," + e.b.centerY();
        String l = !e.label.isEmpty() ? e.label : !e.text.isEmpty() ? e.text : !e.desc.isEmpty() ? e.desc
                : !e.hint.isEmpty() ? e.hint : e.resId;
        return e.cls + (l.isEmpty() ? "" : " \"" + clip(l) + "\"");
    }

    /**
     * Visible nodes whose text, description or hint contains text (case-insensitive; equal when exact),
     * and whose resource id ends with resId when given. Top-most windows first.
     */
    public List<AccessibilityNodeInfo> findNodes(String text, String resId, boolean exact) {
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        String want = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        String rid = resId == null ? "" : resId.trim();
        if (want.isEmpty() && rid.isEmpty()) return found;
        List<AccessibilityWindowInfo> sorted = new ArrayList<>(getWindows());
        Collections.sort(sorted, new Comparator<AccessibilityWindowInfo>() {
            @Override public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return b.getLayer() - a.getLayer();
            }
        });
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        for (AccessibilityWindowInfo w : sorted) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null) roots.add(r);
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        for (AccessibilityNodeInfo r : roots) find(r, 0, want, rid, exact, found);
        return found;
    }

    private static void find(AccessibilityNodeInfo n, int depth, String want, String rid, boolean exact,
                             List<AccessibilityNodeInfo> found) {
        if (n == null || depth > 60 || !n.isVisibleToUser()) return;
        boolean ok = true;
        if (!rid.isEmpty()) {
            String id = n.getViewIdResourceName();
            ok = id != null && (id.equals(rid) || id.endsWith("/" + rid));
        }
        if (ok && !want.isEmpty()) {
            ok = matches(n.getText(), want, exact) || matches(n.getContentDescription(), want, exact)
                    || (n.isEditable() && matches(n.getHintText(), want, exact));
        }
        if (ok) found.add(n);
        for (int i = 0; i < n.getChildCount(); i++) find(n.getChild(i), depth + 1, want, rid, exact, found);
    }

    private static boolean matches(CharSequence c, String want, boolean exact) {
        if (c == null) return false;
        String s = c.toString().trim().toLowerCase(Locale.ROOT);
        return exact ? s.equals(want) : s.contains(want);
    }

    // ------------------------------------------------------------------ actions

    /** Tap the item with this number from the latest screen read (tap_id). */
    public Outcome tapId(int num) {
        StringBuilder why = new StringBuilder();
        Elem e = resolve(num, why);
        if (e == null) return Outcome.fail(why.toString());
        String what = "[" + num + "] " + describe(e);
        if (e.cell) {
            String r = tap(e.b.centerX(), e.b.centerY());
            return r.startsWith("Tapped") ? Outcome.ok("Tapped " + what + ".") : Outcome.fail(r);
        }
        return clickNode(e.node, what);
    }

    /** Click a node (or its nearest clickable parent), falling back to a finger tap at its centre. */
    Outcome clickNode(AccessibilityNodeInfo n, String what) {
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        AccessibilityNodeInfo c = n;
        for (int i = 0; c != null && i < 5; i++) {
            if (c.isClickable() && c.isEnabled()) {
                if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Outcome.ok("Clicked " + what + ".");
                break;
            }
            c = c.getParent();
        }
        String r = tap(b.centerX(), b.centerY());
        return r.startsWith("Tapped") ? Outcome.ok(r + " (finger tap on " + what + ")") : Outcome.fail(r);
    }

    /** Tap the best visible match for text/resId: exact text first, then clickable, then anything. */
    public Outcome tapText(String text, String resId, boolean exact) {
        List<AccessibilityNodeInfo> found = findNodes(text, resId, exact);
        String what = (text == null || text.isEmpty() ? "" : "\"" + text + "\"")
                + (resId == null || resId.isEmpty() ? "" : " (id=" + resId + ")");
        if (found.isEmpty()) return Outcome.fail("Nothing matching " + what.trim() + " is on screen. Nothing was tapped.");
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        String want = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (AccessibilityNodeInfo n : found) {
            int score = 0;
            if (!want.isEmpty() && (matches(n.getText(), want, true) || matches(n.getContentDescription(), want, true))) score += 2;
            if (n.isClickable() || (n.getParent() != null && n.getParent().isClickable())) score += 1;
            if (score > bestScore) { best = n; bestScore = score; }
        }
        return clickNode(best, what.trim());
    }

    /** Result of an action: whether it worked, and a message. */
    public static final class Outcome {
        public final boolean ok;
        public final String msg;
        private Outcome(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
        static Outcome ok(String m) { return new Outcome(true, m); }
        static Outcome fail(String m) { return new Outcome(false, m); }
    }

    public String tap(int x, int y) {
        return gesture(x, y, x, y, 60) ? "Tapped (" + x + "," + y + ")." : "Tap failed.";
    }

    public String longPress(int x, int y, int ms) {
        return gesture(x, y, x, y, Math.max(ms, 600)) ? "Long-pressed (" + x + "," + y + ")." : "Long press failed.";
    }

    public String swipe(int x1, int y1, int x2, int y2, int ms) {
        return gesture(x1, y1, x2, y2, Math.max(ms, 100)) ? "Swiped." : "Swipe failed.";
    }

    /**
     * Swipes inside the largest scrollable area of the app, and never over the keyboard (a swipe
     * that starts on a key types it).
     */
    public String scroll(String direction) {
        Rect a = scrollArea();
        int w = a.width(), h = a.height();
        int cx = a.centerX(), cy = a.centerY();
        switch (direction.toLowerCase(Locale.US)) {
            case "down":  return swipe(cx, a.top + (int) (h * 0.80), cx, a.top + (int) (h * 0.25), 350);  // reveal content below
            case "up":    return swipe(cx, a.top + (int) (h * 0.25), cx, a.top + (int) (h * 0.80), 350);
            case "left":  return swipe(a.left + (int) (w * 0.20), cy, a.left + (int) (w * 0.80), cy, 300);
            case "right": return swipe(a.left + (int) (w * 0.80), cy, a.left + (int) (w * 0.20), cy, 300);
            default: return "direction must be up, down, left or right";
        }
    }

    private Rect scrollArea() {
        DisplayMetrics dm = realMetrics();
        Rect screen = new Rect(0, (int) (dm.heightPixels * 0.06f), dm.widthPixels, (int) (dm.heightPixels * 0.92f));
        int keyboardTop = Integer.MAX_VALUE;
        Rect best = null;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                Rect wb = new Rect();
                w.getBoundsInScreen(wb);
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) { keyboardTop = Math.min(keyboardTop, wb.top); continue; }
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                Rect r = largestScrollable(w.getRoot(), 0);
                if (r != null && (best == null || r.width() * r.height() > best.width() * best.height())) best = r;
            }
        } catch (Exception ignored) { }
        Rect a = new Rect(best != null && best.width() > 100 && best.height() > 150 ? best : screen);
        if (!a.intersect(screen)) a.set(screen);
        if (keyboardTop < a.bottom) a.bottom = Math.max(a.top + 100, keyboardTop - 10);
        return a;
    }

    private static Rect largestScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40 || !n.isVisibleToUser()) return null;
        Rect best = null;
        if (n.isScrollable()) { best = new Rect(); n.getBoundsInScreen(best); }
        for (int i = 0; i < n.getChildCount(); i++) {
            Rect r = largestScrollable(n.getChild(i), depth + 1);
            if (r != null && (best == null || r.width() * r.height() > best.width() * best.height())) best = r;
        }
        return best;
    }

    private boolean gesture(int x1, int y1, int x2, int y2, int ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, ms))
                .build();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok.set(true); done.countDown(); }
            @Override public void onCancelled(GestureDescription d) { done.countDown(); }
        }, main);
        if (!sent) return false;
        try { done.await(ms + 3000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return ok.get();
    }

    /** Type into item [num] from the latest screen read, or into the focused field when num < 0. */
    public Outcome typeText(String text, int num, boolean submit, boolean append) {
        AccessibilityNodeInfo target;
        if (num >= 0) {
            StringBuilder why = new StringBuilder();
            Elem e = resolve(num, why);
            if (e == null) return Outcome.fail(why.toString());
            if (e.cell) return Outcome.fail("[" + num + "] is a screenshot grid cell, not a text field. Tap it, then type without a number.");
            target = e.node;
            if (!target.isEditable())
                return Outcome.fail("[" + num + "] " + describe(e) + " is not a text field. Nothing was typed.");
        } else {
            target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (target == null) return Outcome.fail("No text field is focused. Tap a field first or pass its number.");
        if (!target.isFocused()) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        String value = text;
        if (append && target.getText() != null && !target.isShowingHintText()) value = target.getText() + text;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) return Outcome.fail("This field did not accept text.");
        String r = "Typed \"" + text + "\".";
        if (submit) {
            boolean entered = target.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            r += entered ? " Pressed Enter." : " Enter was not accepted; tap the submit button instead.";
        }
        return Outcome.ok(r);
    }

    public String pressButton(String button) {
        int action;
        switch (button.toLowerCase(Locale.US)) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock_screen": action = GLOBAL_ACTION_LOCK_SCREEN; break;
            default: return "Unknown button. Use back, home, recents, notifications, quick_settings or lock_screen.";
        }
        return performGlobalAction(action) ? "Pressed " + button + "." : "Could not press " + button + ".";
    }

    // ------------------------------------------------------------------ apps

    public String listApps(String filter) {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(i, 0);
        List<String> rows = new ArrayList<>();
        String f = filter == null ? "" : filter.toLowerCase(Locale.US);
        for (ResolveInfo ri : apps) {
            String label = ri.loadLabel(pm).toString();
            String pkg = ri.activityInfo.packageName;
            if (f.isEmpty() || label.toLowerCase(Locale.US).contains(f) || pkg.toLowerCase(Locale.US).contains(f)) {
                rows.add(label + "  (" + pkg + ")");
            }
        }
        Collections.sort(rows, String.CASE_INSENSITIVE_ORDER);
        if (rows.isEmpty()) return "No apps match \"" + filter + "\".";
        StringBuilder sb = new StringBuilder();
        for (String r : rows) sb.append(r).append('\n');
        return sb.toString();
    }

    /** Launch by package name, or by app name ("WhatsApp"). */
    public String launchApp(String app) {
        PackageManager pm = getPackageManager();
        String pkg = app;
        Intent launch = pm.getLaunchIntentForPackage(app);
        if (launch == null) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            String want = app.toLowerCase(Locale.US).trim();
            ResolveInfo best = null;
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String label = ri.loadLabel(pm).toString().toLowerCase(Locale.US);
                if (label.equals(want)) { best = ri; break; }
                if (best == null && label.contains(want)) best = ri;
            }
            if (best == null) return "No app called \"" + app + "\". Use list_apps.";
            pkg = best.activityInfo.packageName;
            launch = pm.getLaunchIntentForPackage(pkg);
        }
        if (launch == null) return "App " + pkg + " has no launch screen.";
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(launch);
        return "Launched " + pkg + ".";
    }

    public String openUrl(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return "Opened " + url + ".";
        } catch (Exception e) {
            return "Nothing on the phone can open " + url + ".";
        }
    }

    // ------------------------------------------------------------------ screenshots

    public static final class Shot {
        public byte[] jpeg;
        public int width, height, screenWidth, screenHeight;
        public String error;
    }

    public Shot screenshot(int maxSide) { return screenshot(maxSide, null); }

    /**
     * Screenshot as a JPEG scaled so the longest side is at most maxSide. Android 11+.
     * When labels is given, each item is drawn as a numbered box (the numbers tap_id uses).
     */
    public Shot screenshot(int maxSide, List<Elem> labels) {
        final Shot shot = new Shot();
        final AtomicReference<Bitmap> bmp = new AtomicReference<>();
        final AtomicReference<String> err = new AtomicReference<>();
        for (int attempt = 0; attempt < 3 && bmp.get() == null; attempt++) {
            final CountDownLatch done = new CountDownLatch(1);
            err.set(null);
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    HardwareBuffer hb = r.getHardwareBuffer();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                    if (hw != null) bmp.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                    hb.close();
                    done.countDown();
                }
                @Override public void onFailure(int code) {
                    err.set("screenshot error code " + code
                            + (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ? " (too frequent)" : "")
                            + (code == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ? " (this screen blocks screenshots)" : ""));
                    done.countDown();
                }
            });
            try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            if (bmp.get() == null) sleep(1100); // Android allows about one screenshot per second
        }
        Bitmap b = bmp.get();
        if (b == null) { shot.error = err.get() == null ? "screenshot timed out" : err.get(); return shot; }
        shot.screenWidth = b.getWidth();
        shot.screenHeight = b.getHeight();
        float scale = Math.min(1f, (float) maxSide / Math.max(b.getWidth(), b.getHeight()));
        Bitmap scaled = scale < 1f
                ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true)
                : b;
        if (labels != null && !labels.isEmpty()) {
            if (!scaled.isMutable()) scaled = scaled.copy(Bitmap.Config.ARGB_8888, true);
            drawLabels(new Canvas(scaled), labels, (float) scaled.getWidth() / b.getWidth());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out);
        shot.jpeg = out.toByteArray();
        shot.width = scaled.getWidth();
        shot.height = scaled.getHeight();
        return shot;
    }

    private static final int[] BOX_COLORS = {
            Color.rgb(230, 25, 75), Color.rgb(0, 130, 200), Color.rgb(60, 180, 75), Color.rgb(245, 130, 48),
            Color.rgb(145, 30, 180), Color.rgb(0, 150, 150), Color.rgb(200, 20, 160), Color.rgb(120, 90, 0)};

    /** Numbered boxes: items get a coloured outline and tag, grid cells a faint outline and centred number. */
    private static void drawLabels(Canvas c, List<Elem> items, float f) {
        float density = Math.max(1f, c.getWidth() / 400f);
        Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        box.setStyle(Paint.Style.STROKE);
        Paint tagBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Color.WHITE);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        Paint.FontMetrics fm;
        for (Elem e : items) {
            RectF r = new RectF(e.b.left * f, e.b.top * f, e.b.right * f, e.b.bottom * f);
            String label = String.valueOf(e.num);
            if (e.cell) {
                box.setColor(Color.argb(110, 255, 255, 255));
                box.setStrokeWidth(1f);
                c.drawRect(r, box);
                txt.setTextSize(9f * density);
                float w = txt.measureText(label) + 4 * density;
                fm = txt.getFontMetrics();
                float h = fm.descent - fm.ascent + 2 * density;
                tagBg.setColor(Color.argb(150, 0, 0, 0));
                RectF t = new RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2);
                c.drawRoundRect(t, 3 * density, 3 * density, tagBg);
                c.drawText(label, t.left + 2 * density, t.top + density - fm.ascent, txt);
                continue;
            }
            int col = BOX_COLORS[e.num % BOX_COLORS.length];
            box.setColor(col);
            box.setStrokeWidth(2f * density / 2);
            c.drawRect(r, box);
            txt.setTextSize(10f * density);
            fm = txt.getFontMetrics();
            float w = txt.measureText(label) + 4 * density;
            float h = fm.descent - fm.ascent + 2 * density;
            float top = r.top - h >= 0 ? r.top - h : r.top;  // above the box if there is room
            RectF t = new RectF(r.left, top, r.left + w, top + h);
            tagBg.setColor(col);
            c.drawRect(t, tagBg);
            c.drawText(label, t.left + 2 * density, t.top + density - fm.ascent, txt);
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ------------------------------------------------------------------ status overlay

    /** Small floating bar with a Stop button, shown while Claude is in control. */
    public void showOverlay(final String status) {
        main.post(new Runnable() {
            @Override public void run() {
                if (overlay == null) {
                    LinearLayout bar = new LinearLayout(PilotService.this);
                    bar.setOrientation(LinearLayout.HORIZONTAL);
                    bar.setBackgroundColor(Color.argb(200, 30, 30, 30));
                    bar.setPadding(16, 8, 8, 8);
                    overlayText = new TextView(PilotService.this);
                    overlayText.setTextColor(Color.WHITE);
                    overlayText.setTextSize(12);
                    overlayText.setMaxLines(2);
                    bar.addView(overlayText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    Button stop = new Button(PilotService.this);
                    stop.setText("Stop");
                    stop.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { agent.stop(); relay.stop(); remote.stop(); hideOverlay(); }
                    });
                    bar.addView(stop);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, // taps don't reset the screen timeout
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.BOTTOM;
                    lp.y = 220;
                    ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(bar, lp);
                    overlay = bar;
                }
                overlayText.setText(status);
                // Let the screen sleep again after 3 idle minutes without commands.
                main.removeCallbacks(idleHide);
                main.postDelayed(idleHide, 3 * 60 * 1000);
            }
        });
    }

    private final Runnable idleHide = new Runnable() {
        @Override public void run() { if (!agent.isRunning()) hideOverlay(); }
    };

    public void hideOverlay() {
        main.post(new Runnable() {
            @Override public void run() {
                if (overlay != null) {
                    try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay); }
                    catch (Exception ignored) { }
                    overlay = null;
                }
            }
        });
    }
}
