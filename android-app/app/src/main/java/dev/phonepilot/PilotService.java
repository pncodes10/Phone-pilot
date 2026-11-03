package dev.phonepilot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
import java.util.List;
import java.util.Locale;
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

    /** Elements from the last read of the screen, so Claude can refer to them by index. */
    private final List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();

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

    public static final class Screen {
        public String text;
        public String packageName;
    }

    /** Reads every visible window (skipping our own overlay) into an indexed element list. */
    public synchronized Screen readScreen() {
        lastNodes.clear();
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        String activePkg = "";
        List<AccessibilityWindowInfo> windows = getWindows();
        // Draw order: higher layer is on top. List the top-most windows first.
        List<AccessibilityWindowInfo> sorted = new ArrayList<>(windows);
        Collections.sort(sorted, new Comparator<AccessibilityWindowInfo>() {
            @Override public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return b.getLayer() - a.getLayer();
            }
        });
        for (AccessibilityWindowInfo w : sorted) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null) continue;
            if (w.isActive() && r.getPackageName() != null) activePkg = r.getPackageName().toString();
            roots.add(r);
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) {
                roots.add(r);
                if (r.getPackageName() != null) activePkg = r.getPackageName().toString();
            }
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        StringBuilder sb = new StringBuilder();
        sb.append("Foreground app: ").append(activePkg)
          .append("\nScreen size: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
          .append(" px (tap/swipe coordinates use these pixels)\nElements:\n");
        for (AccessibilityNodeInfo r : roots) collect(r, sb, 0);
        if (lastNodes.isEmpty()) sb.append("(no readable elements: the app may draw its own UI, use screenshot)\n");

        Screen s = new Screen();
        s.text = sb.toString();
        s.packageName = activePkg;
        return s;
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 60) return;
        if (!n.isVisibleToUser()) return;
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        CharSequence hint = n.getHintText();
        String id = n.getViewIdResourceName();
        boolean interesting = n.isClickable() || n.isLongClickable() || n.isEditable()
                || n.isCheckable() || n.isScrollable()
                || (text != null && text.length() > 0) || (desc != null && desc.length() > 0);
        if (interesting) {
            int idx = lastNodes.size();
            lastNodes.add(n);
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String cls = n.getClassName() == null ? "View" : n.getClassName().toString();
            cls = cls.substring(cls.lastIndexOf('.') + 1);
            sb.append('[').append(idx).append("] ").append(cls);
            if (text != null && text.length() > 0) sb.append(" text=\"").append(clip(text)).append('"');
            if (desc != null && desc.length() > 0) sb.append(" desc=\"").append(clip(desc)).append('"');
            if (hint != null && hint.length() > 0 && n.isEditable()) sb.append(" hint=\"").append(clip(hint)).append('"');
            if (id != null) sb.append(" id=").append(id.substring(id.indexOf('/') + 1));
            sb.append(" @(").append(b.centerX()).append(',').append(b.centerY()).append(')');
            List<String> flags = new ArrayList<>();
            if (n.isClickable()) flags.add("clickable");
            if (n.isEditable()) flags.add("editable");
            if (n.isScrollable()) flags.add("scrollable");
            if (n.isCheckable()) flags.add(n.isChecked() ? "checked" : "unchecked");
            if (n.isFocused()) flags.add("focused");
            if (!n.isEnabled()) flags.add("disabled");
            if (n.isPassword()) flags.add("password");
            if (!flags.isEmpty()) sb.append(' ').append(join(flags));
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
    }

    private static String clip(CharSequence c) {
        String s = c.toString().replace('\n', ' ');
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        return sb.toString();
    }

    public synchronized AccessibilityNodeInfo node(int index) {
        if (index < 0 || index >= lastNodes.size()) return null;
        AccessibilityNodeInfo n = lastNodes.get(index);
        n.refresh();
        return n;
    }

    // ------------------------------------------------------------------ actions

    public String tapElement(int index) {
        AccessibilityNodeInfo n = node(index);
        if (n == null) return "No element " + index + ". Call get_screen again.";
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        // Prefer a real click on the element (or its nearest clickable parent).
        AccessibilityNodeInfo c = n;
        for (int i = 0; c != null && i < 5; i++) {
            if (c.isClickable() && c.isEnabled()) {
                if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return "Clicked element " + index + ".";
                }
                break;
            }
            c = c.getParent();
        }
        // Fall back to a finger tap at the element's centre.
        return tap(b.centerX(), b.centerY()) + " (gesture tap on element " + index + ")";
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

    public String scroll(String direction) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int cx = w / 2, cy = h / 2;
        switch (direction.toLowerCase(Locale.US)) {
            case "down":  return swipe(cx, (int) (h * 0.75), cx, (int) (h * 0.30), 350);  // reveal content below
            case "up":    return swipe(cx, (int) (h * 0.30), cx, (int) (h * 0.75), 350);
            case "left":  return swipe((int) (w * 0.20), cy, (int) (w * 0.80), cy, 300);
            case "right": return swipe((int) (w * 0.80), cy, (int) (w * 0.20), cy, 300);
            default: return "direction must be up, down, left or right";
        }
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

    public String typeText(String text, int index, boolean submit, boolean append) {
        AccessibilityNodeInfo target = index >= 0 ? node(index) : null;
        if (target == null) target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null) return "No text field is focused. Tap a field first or pass its index.";
        if (!target.isFocused()) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        String value = text;
        if (append && target.getText() != null && !target.isShowingHintText()) value = target.getText() + text;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) return "This field did not accept text.";
        String r = "Typed \"" + text + "\".";
        if (submit) {
            boolean entered = target.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            r += entered ? " Pressed Enter." : " Enter was not accepted; tap the submit button instead.";
        }
        return r;
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

    /** Screenshot as a JPEG scaled so the longest side is at most maxSide. Android 11+. */
    public Shot screenshot(int maxSide) {
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out);
        shot.jpeg = out.toByteArray();
        shot.width = scaled.getWidth();
        shot.height = scaled.getHeight();
        return shot;
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
