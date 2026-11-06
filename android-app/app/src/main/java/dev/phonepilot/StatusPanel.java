package dev.phonepilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What the user sees while Claude controls the phone:
 *  - a small strip over the left half of the status bar with the task and the current action, and a
 *    Stop button (the right half stays free, so the notification shade can still be pulled down);
 *  - when Claude needs the user, an orange card with the message and a Done button, plus a
 *    notification that rings and vibrates and has its own Done button (it works on the lock screen
 *    and in system screens where the card is hidden).
 * Both windows let Claude's own gestures pass through them.
 */
final class StatusPanel {

    static final String ACTION_DONE = "dev.phonepilot.ASK_DONE";
    private static final int NOTIFY_ID = 7001;
    private static final long IDLE_HIDE_MS = 60_000;

    private final PilotService svc;
    private final Handler main = new Handler(Looper.getMainLooper());

    private View bar, card;
    private TextView taskView, actionView, cardText;
    private WindowManager.LayoutParams barLp, cardLp;

    private volatile String task = "", action = "";
    private volatile String askMessage;          // null when not asking
    private volatile long askId, answeredId, askStart;
    private volatile long lastActivity;
    private volatile boolean sensitive, passThrough;
    private final Object askLock = new Object();
    private BroadcastReceiver doneReceiver;

    StatusPanel(PilotService svc) { this.svc = svc; }

    // ------------------------------------------------------------------ public API (any thread)

    /** The task line, e.g. "Testing login (3/8)". Empty to clear. */
    void setTask(String t) {
        task = t == null ? "" : t.trim();
        touch();
    }

    /** The current action, e.g. "Tapping \"Save\"…". */
    void setAction(String a) {
        action = a == null ? "" : a;
        touch();
    }

    /** Show a request to the user; returns its id. alert: sound, vibrate or none. */
    long ask(String message, String alert) {
        synchronized (askLock) {
            askId++;
            askMessage = message;
            askStart = SystemClock.uptimeMillis();
        }
        notifyUser(message, alert);
        touch();
        return askId;
    }

    /** Wait up to ms for the user to tap Done on the current request. Returns true if they did. */
    boolean waitAnswer(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        synchronized (askLock) {
            while (!answered()) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) return false;
                try { askLock.wait(left); } catch (InterruptedException e) { return answered(); }
            }
            return true;
        }
    }

    boolean asking() { return askMessage != null; }
    boolean answered() { return askId > 0 && answeredId == askId; }
    long askAgeMs() { return SystemClock.uptimeMillis() - askStart; }

    /** Remove the request (card and notification) without it counting as answered. */
    void clearAsk() {
        askMessage = null;
        cancelNotification();
        refresh();
    }

    /** Called when the foreground app changes. System screens ignore taps while we draw on top. */
    void onForeground(String pkg) {
        sensitive = PilotService.sensitive(pkg);
        refresh();
    }

    /** Make both windows ignore touches while Claude's gesture runs, so it reaches the app below. */
    void passThrough(final boolean on) {
        if (bar == null && card == null) return;
        passThrough = on;
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                applyTouchable(bar, barLp);
                applyTouchable(card, cardLp);
                done.countDown();
            }
        });
        try { done.await(300, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
    }

    void hide() {
        task = "";
        action = "";
        lastActivity = 0;
        clearAsk();
    }

    void register() {
        doneReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { markAnswered(); }
        };
        IntentFilter f = new IntentFilter(ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33) svc.registerReceiver(doneReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else svc.registerReceiver(doneReceiver, f);
    }

    void unregister() {
        try { if (doneReceiver != null) svc.unregisterReceiver(doneReceiver); } catch (Exception ignored) { }
        hide();
    }

    // ------------------------------------------------------------------ internals

    private void markAnswered() {
        synchronized (askLock) {
            answeredId = askId;
            askMessage = null;
            askLock.notifyAll();
        }
        Log.add("The user tapped Done.");
        cancelNotification();
        refresh();
    }

    private void touch() {
        lastActivity = SystemClock.uptimeMillis();
        refresh();
        main.removeCallbacks(idleCheck);
        main.postDelayed(idleCheck, IDLE_HIDE_MS + 500);
    }

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() { refresh(); }
    };

    private void refresh() { main.post(sync); }

    private final Runnable sync = new Runnable() {
        @Override public void run() {
            boolean active = lastActivity > 0 && SystemClock.uptimeMillis() - lastActivity < IDLE_HIDE_MS;
            boolean wantBar = !sensitive && (active || svc.agent.isRunning() || askMessage != null);
            boolean wantCard = !sensitive && askMessage != null;
            if (wantBar) {
                ensureBar();
                taskView.setText(task);
                taskView.setVisibility(task.isEmpty() ? View.GONE : View.VISIBLE);
                actionView.setText(action.isEmpty() ? "Claude is controlling this phone" : "Claude: " + action);
            } else {
                removeView(bar);
                bar = null;
            }
            if (wantCard) {
                ensureCard();
                cardText.setText(askMessage);
            } else {
                removeView(card);
                card = null;
            }
        }
    };

    private WindowManager wm() { return (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE); }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, svc.getResources().getDisplayMetrics()));
    }

    private int statusBarHeight() {
        int id = svc.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? svc.getResources().getDimensionPixelSize(id) : dp(28);
    }

    private WindowManager.LayoutParams params(int width) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return lp;
    }

    private void applyTouchable(View v, WindowManager.LayoutParams lp) {
        if (v == null || lp == null) return;
        if (passThrough) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm().updateViewLayout(v, lp); } catch (Exception ignored) { }
    }

    private void ensureBar() {
        if (bar != null) return;
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int sb = statusBarHeight();
        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(2), 0);
        row.setMinimumHeight(sb);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 20, 20, 24));
        bg.setCornerRadii(new float[]{0, 0, 0, 0, dp(12), dp(12), 0, 0});
        row.setBackground(bg);

        LinearLayout texts = new LinearLayout(svc);
        texts.setOrientation(LinearLayout.VERTICAL);
        taskView = new TextView(svc);
        taskView.setTextColor(Color.rgb(255, 196, 120));
        taskView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        taskView.setSingleLine(true);
        taskView.setEllipsize(TextUtils.TruncateAt.END);
        actionView = new TextView(svc);
        actionView.setTextColor(Color.WHITE);
        actionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        actionView.setSingleLine(true);
        actionView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(taskView);
        texts.addView(actionView);
        // Stay left of a centre camera hole and leave the right half for pulling down the shade.
        int maxText = dm.widthPixels / 2 - dp(24) - dp(52);
        taskView.setMaxWidth(maxText);
        actionView.setMaxWidth(maxText);
        row.addView(texts, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button stop = new Button(svc);
        stop.setText("Stop");
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        stop.setTextColor(Color.WHITE);
        stop.setMinHeight(0);
        stop.setMinimumHeight(0);
        stop.setMinWidth(0);
        stop.setMinimumWidth(0);
        stop.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(Color.rgb(190, 40, 40));
        sbg.setCornerRadius(dp(10));
        stop.setBackground(sbg);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Log.add("Stopped from the status strip.");
                svc.agent.stop();
                svc.relay.stop();
                svc.remote.stop();
                hide();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                Math.max(dp(20), sb - dp(8)));
        slp.leftMargin = dp(8);
        row.addView(stop, slp);

        barLp = params(WindowManager.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP | Gravity.START;
        barLp.x = 0;
        barLp.y = 0;
        if (passThrough) barLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm().addView(row, barLp); bar = row; }
        catch (Exception e) { Log.add("Status strip failed: " + e); }
    }

    private void ensureCard() {
        if (card != null) return;
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(230, 120, 20));
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        box.setElevation(dp(6));

        LinearLayout texts = new LinearLayout(svc);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(svc);
        title.setText("Claude needs you");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setAlpha(0.85f);
        cardText = new TextView(svc);
        cardText.setTextColor(Color.WHITE);
        cardText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cardText.setMaxLines(5);
        cardText.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        texts.addView(cardText);
        box.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button done = new Button(svc);
        done.setText("Done");
        done.setTextColor(Color.rgb(230, 120, 20));
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setColor(Color.WHITE);
        dbg.setCornerRadius(dp(18));
        done.setBackground(dbg);
        done.setMinHeight(0);
        done.setMinimumHeight(0);
        done.setPadding(dp(16), dp(6), dp(16), dp(6));
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { markAnswered(); }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(10);
        box.addView(done, dlp);

        cardLp = params(WindowManager.LayoutParams.MATCH_PARENT);
        cardLp.gravity = Gravity.TOP;
        cardLp.y = statusBarHeight() + dp(6);
        cardLp.horizontalMargin = 0.03f;
        if (passThrough) cardLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm().addView(box, cardLp); card = box; }
        catch (Exception e) { Log.add("Request card failed: " + e); }
    }

    private void removeView(View v) {
        if (v == null) return;
        try { wm().removeView(v); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ notification, sound, vibration

    private void notifyUser(String message, String alert) {
        NotificationManager nm = (NotificationManager) svc.getSystemService(Context.NOTIFICATION_SERVICE);
        String channel = "none".equals(alert) ? "ask_silent" : "vibrate".equals(alert) ? "ask_vibrate" : "ask_sound";
        ensureChannels(nm);
        boolean posted = false;
        if (nm.areNotificationsEnabled()) {
            try {
                PendingIntent doneIntent = PendingIntent.getBroadcast(svc, 1,
                        new Intent(ACTION_DONE).setPackage(svc.getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Intent open = svc.getPackageManager().getLaunchIntentForPackage(svc.getPackageName());
                Notification.Builder b = new Notification.Builder(svc, channel)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle("Claude needs you")
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setOnlyAlertOnce(false)
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .addAction(new Notification.Action.Builder(null, "Done", doneIntent).build());
                if (open != null) b.setContentIntent(PendingIntent.getActivity(svc, 2, open, PendingIntent.FLAG_IMMUTABLE));
                nm.notify(NOTIFY_ID, b.build());
                posted = true;
            } catch (Exception e) {
                Log.add("Notification failed: " + e);
            }
        }
        if (!posted) {
            // No notification permission: ring and vibrate directly.
            if ("sound".equals(alert) || alert == null) {
                try {
                    Ringtone r = RingtoneManager.getRingtone(svc, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                    if (r != null) r.play();
                } catch (Exception ignored) { }
            }
            if (!"none".equals(alert)) vibrate();
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        try {
            Vibrator v = (Vibrator) svc.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 150, 250, 150, 400}, -1));
        } catch (Exception ignored) { }
    }

    private void ensureChannels(NotificationManager nm) {
        if (nm.getNotificationChannel("ask_sound") != null) return;
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        long[] pattern = {0, 250, 150, 250, 150, 400};

        NotificationChannel loud = new NotificationChannel("ask_sound", "Claude needs you (sound)", NotificationManager.IMPORTANCE_HIGH);
        loud.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), aa);
        loud.enableVibration(true);
        loud.setVibrationPattern(pattern);
        NotificationChannel vib = new NotificationChannel("ask_vibrate", "Claude needs you (vibrate)", NotificationManager.IMPORTANCE_HIGH);
        vib.setSound(null, null);
        vib.enableVibration(true);
        vib.setVibrationPattern(pattern);
        NotificationChannel quiet = new NotificationChannel("ask_silent", "Claude needs you (silent)", NotificationManager.IMPORTANCE_DEFAULT);
        quiet.setSound(null, null);
        quiet.enableVibration(false);
        nm.createNotificationChannel(loud);
        nm.createNotificationChannel(vib);
        nm.createNotificationChannel(quiet);
    }

    private void cancelNotification() {
        try {
            ((NotificationManager) svc.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFY_ID);
        } catch (Exception ignored) { }
    }
}
