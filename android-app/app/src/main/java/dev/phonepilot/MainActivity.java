package dev.phonepilot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;

/** Setup screen: enable the service, run a task on the phone, or turn on the Wi-Fi bridge. */
public class MainActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView status, bridgeInfo, logView, report, relayInfo;
    private EditText apiKey, model, task, maxSteps, relayServer, relayToken;
    private Button runBtn, bridgeBtn, relayBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pilot", MODE_PRIVATE);
        if (!prefs.contains("token")) prefs.edit().putString("token", newToken()).apply();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(heading("Phone Pilot"));
        status = new TextView(this);
        root.addView(status);
        Button access = button("Open Accessibility settings");
        access.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(MainActivity.this,
                        "Installed apps → Phone Pilot → turn on", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(access);

        // ---- relay mode (Claude in the Claude app, no API key)
        if (!prefs.contains("pairing")) prefs.edit().putString("pairing", RelayClient.newPairingCode()).apply();
        root.addView(heading("1. Let Claude in the Claude app control this phone"));
        root.addView(label("No API key needed. Tap Start, copy the pairing code and paste it into your "
                + "Claude chat. Claude sends commands through the relay server; everything is encrypted "
                + "with the pairing code."));
        root.addView(label("Relay server"));
        relayServer = field(prefs.getString("relayServer", "https://ntfy.sh"), false);
        root.addView(relayServer);
        root.addView(label("Relay access token (optional, from your free ntfy.sh account; avoids the shared daily limit)"));
        relayToken = field(prefs.getString("relayToken", ""), false);
        relayToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(relayToken);
        relayBtn = button("Start relay mode");
        relayBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRelay(); }
        });
        root.addView(relayBtn);
        relayInfo = new TextView(this);
        relayInfo.setTextIsSelectable(true);
        relayInfo.setTypeface(Typeface.MONOSPACE);
        root.addView(relayInfo);
        Button copy = button("Copy pairing code");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Phone Pilot pairing", pairingText()));
                toast("Copied. Paste it into your Claude chat.");
            }
        });
        root.addView(copy);
        Button newCode = button("New pairing code (disconnects the old one)");
        newCode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PilotService s = PilotService.get();
                if (s != null) s.relay.stop();
                prefs.edit().putString("pairing", RelayClient.newPairingCode()).apply();
                refresh();
            }
        });
        root.addView(newCode);

        // ---- on-phone agent
        root.addView(heading("2. Or let Claude run a task here with your API key"));
        root.addView(label("Anthropic API key (console.anthropic.com)"));
        apiKey = field(prefs.getString("apiKey", ""), false);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(apiKey);
        root.addView(label("Model"));
        model = field(prefs.getString("model", AgentRunner.DEFAULT_MODEL), false);
        root.addView(model);
        root.addView(label("Task"));
        task = field(prefs.getString("task",
                "Open the Settings app, find the Display settings and tell me whether dark theme is on."), true);
        root.addView(task);
        root.addView(label("Max steps"));
        maxSteps = field(prefs.getString("maxSteps", "40"), false);
        maxSteps.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(maxSteps);
        runBtn = button("Run");
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRun(); }
        });
        root.addView(runBtn);
        report = new TextView(this);
        report.setTextIsSelectable(true);
        root.addView(report);

        // ---- Wi-Fi bridge
        root.addView(heading("3. Or control it from Claude on a computer (same Wi-Fi)"));
        root.addView(label("Starts a local server so phone_app_mcp.py on a computer on the same Wi-Fi "
                + "can drive this phone. Only turn it on while you use it."));
        bridgeBtn = button("Start Wi-Fi bridge");
        bridgeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleBridge(); }
        });
        root.addView(bridgeBtn);
        bridgeInfo = new TextView(this);
        bridgeInfo.setTextIsSelectable(true);
        bridgeInfo.setTypeface(Typeface.MONOSPACE);
        root.addView(bridgeInfo);

        // ---- log
        root.addView(heading("Activity"));
        Button clear = button("Clear log");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Log.clear(); }
        });
        root.addView(clear);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.setListener(new Log.Listener() {
            @Override public void onLog() {
                main.post(new Runnable() { @Override public void run() { refresh(); } });
            }
        });
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.setListener(null);
        save();
    }

    private void refresh() {
        PilotService s = PilotService.get();
        status.setText(s == null
                ? "⚠ Accessibility service is OFF. Turn on \"Phone Pilot\" in Accessibility settings."
                : "✓ Accessibility service is on.");
        runBtn.setText(s != null && s.agent.isRunning() ? "Stop" : "Run");
        boolean bridge = s != null && s.remote.isRunning();
        bridgeBtn.setText(bridge ? "Stop Wi-Fi bridge" : "Start Wi-Fi bridge");
        bridgeInfo.setText(bridge
                ? "PHONE_URL=http://" + ipAddress() + ":" + RemoteServer.PORT + "\nPHONE_TOKEN=" + prefs.getString("token", "")
                : "");
        boolean relay = s != null && s.relay.isRunning();
        relayBtn.setText(relay ? "Stop relay mode" : "Start relay mode");
        relayInfo.setText(relay ? "Listening. Pairing code:\n" + pairingText() : "Off. Pairing code:\n" + pairingText());
        logView.setText(Log.text());
    }

    private String pairingText() {
        String t = relayToken.getText().toString().trim();
        return "phonepilot:" + relayServer.getText().toString().trim() + (t.isEmpty() ? "" : "?auth=" + t)
                + "#" + prefs.getString("pairing", "");
    }

    private void toggleRelay() {
        PilotService s = PilotService.get();
        if (s == null) { toast("Turn on the accessibility service first."); return; }
        if (s.relay.isRunning()) {
            s.relay.stop();
        } else {
            save();
            try { s.relay.start(relayServer.getText().toString().trim(), prefs.getString("pairing", ""), relayToken.getText().toString().trim()); }
            catch (Exception e) { toast("Could not start: " + e.getMessage()); }
        }
        refresh();
    }

    private void toggleRun() {
        PilotService s = PilotService.get();
        if (s == null) { toast("Turn on the accessibility service first."); return; }
        if (s.agent.isRunning()) { s.agent.stop(); refresh(); return; }
        save();
        String key = apiKey.getText().toString().trim();
        if (key.isEmpty()) { toast("Enter your Anthropic API key."); return; }
        int steps;
        try { steps = Integer.parseInt(maxSteps.getText().toString().trim()); } catch (Exception e) { steps = 40; }
        report.setText("Running… switch to any app; the bar at the bottom shows progress and has a Stop button.");
        s.agent.start(key, model.getText().toString().trim(), task.getText().toString().trim(), steps,
                new AgentRunner.Listener() {
                    @Override public void onFinished(final String r) {
                        main.post(new Runnable() { @Override public void run() {
                            report.setText("Result:\n" + r);
                            refresh();
                        } });
                        // Bring the app back so the report is visible.
                        Intent back = new Intent(MainActivity.this, MainActivity.class);
                        back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        PilotService svc = PilotService.get();
                        if (svc != null) svc.startActivity(back);
                    }
                });
        refresh();
    }

    private void toggleBridge() {
        PilotService s = PilotService.get();
        if (s == null) { toast("Turn on the accessibility service first."); return; }
        if (s.remote.isRunning()) {
            s.remote.stop();
        } else {
            try { s.remote.start(prefs.getString("token", "")); }
            catch (Exception e) { toast("Could not start: " + e.getMessage()); }
        }
        refresh();
    }

    private void save() {
        prefs.edit()
                .putString("apiKey", apiKey.getText().toString().trim())
                .putString("model", model.getText().toString().trim())
                .putString("task", task.getText().toString())
                .putString("maxSteps", maxSteps.getText().toString().trim())
                .putString("relayServer", relayServer.getText().toString().trim())
                .putString("relayToken", relayToken.getText().toString().trim())
                .apply();
    }

    // ------------------------------------------------------------------ helpers

    private static String newToken() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String ipAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && (ni.getName().startsWith("wlan") || a.isSiteLocalAddress())) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "<phone-ip>";
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView heading(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(18);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(18), 0, dp(6));
        return v;
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setPadding(0, dp(6), 0, 0);
        return v;
    }

    private EditText field(String value, boolean multiline) {
        EditText e = new EditText(this);
        e.setText(value);
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(3);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    private Button button(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        return b;
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_LONG).show(); }
}
