package dev.phonepilot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** In-memory activity log shown in the app (last 400 lines). */
public final class Log {
    public interface Listener { void onLog(); }

    private static final List<String> lines = new ArrayList<>();
    private static Listener listener;
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Log() { }

    public static synchronized void add(String msg) {
        lines.add(fmt.format(new Date()) + "  " + msg);
        while (lines.size() > 400) lines.remove(0);
        android.util.Log.i("PhonePilot", msg);
        if (listener != null) listener.onLog();
    }

    public static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    public static synchronized void setListener(Listener l) { listener = l; }

    public static synchronized void clear() { lines.clear(); if (listener != null) listener.onLog(); }
}
