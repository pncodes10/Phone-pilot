package dev.phonepilot;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Relay mode: lets Claude (in the Claude app / claude.ai, no API key) control this phone.
 *
 * The phone and Claude never connect to each other directly. Both talk to a relay server
 * that speaks the ntfy protocol (https://ntfy.sh, free and account-less, or a self-hosted ntfy):
 *
 *   Claude ──PUT command──▶ relay topic "<id>-cmd" ──stream──▶ phone runs the tool
 *   Claude ◀──stream──────  relay topic "<id>-res" ◀──PUT result── phone
 *
 * Every message is encrypted and authenticated with AES-256-GCM using the pairing code, so
 * the relay only ever sees random bytes, and nobody without the code can send commands.
 */
public final class RelayClient {

    private final PilotService svc;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile HttpURLConnection stream;

    private String server;
    private String token = ""; // optional ntfy access token (tk_...), so limits count per account, not per IP
    private byte[] key;
    private String cmdTopic, resTopic;

    RelayClient(PilotService svc) { this.svc = svc; }

    public boolean isRunning() { return running; }

    // ------------------------------------------------------------------ pairing code

    public static String newPairingCode() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return hex(k);
    }

    /** Topic id derived from the key, so knowing the topic does not reveal the key. */
    static String topicId(byte[] key) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(concat("phonepilot-topic".getBytes(StandardCharsets.UTF_8), key));
        return "pp" + hex(h).substring(0, 30);
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start(String serverUrl, String pairingCode) throws Exception {
        start(serverUrl, pairingCode, "");
    }

    public synchronized void start(String serverUrl, String pairingCode, String accessToken) throws Exception {
        if (running) return;
        server = serverUrl.replaceAll("/+$", "");
        token = accessToken == null ? "" : accessToken.trim();
        key = unhex(pairingCode.trim());
        if (key.length != 32) throw new IllegalArgumentException("pairing code must be 64 hex characters");
        String id = topicId(key);
        cmdTopic = id + "-cmd";
        resTopic = id + "-res";
        running = true;
        thread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "relay");
        thread.start();
        Log.add("Relay mode on. Waiting for Claude's commands via " + server);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        HttpURLConnection c = stream;
        if (c != null) c.disconnect();
        Thread t = thread;
        if (t != null) t.interrupt();
        svc.hideOverlay();
        Log.add("Relay mode off.");
    }

    // ------------------------------------------------------------------ main loop

    private void loop() {
        // Only accept commands sent after we started listening (no replay of old ones).
        String since = String.valueOf(System.currentTimeMillis() / 1000);
        int backoff = 2000;
        while (running) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(server + "/" + cmdTopic + "/json?since=" + since).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(120000); // ntfy sends a keepalive every ~45 s
                auth(c);
                stream = c;
                if (c.getResponseCode() != 200) throw new Exception("relay answered " + c.getResponseCode());
                backoff = 2000;
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    JSONObject ev = new JSONObject(line);
                    if (!"message".equals(ev.optString("event"))) continue;
                    since = ev.getString("id");
                    handle(ev);
                }
                c.disconnect();
            } catch (Exception e) {
                if (!running) break;
                Log.add("Relay connection problem, retrying: " + e.getMessage());
                PilotService.sleep(backoff);
                backoff = Math.min(backoff * 2, 60000);
            }
        }
    }

    private void handle(JSONObject ev) {
        String reqId = "?";
        try {
            String payload = body(ev);
            JSONObject cmd;
            try {
                cmd = new JSONObject(new String(decrypt(payload), StandardCharsets.UTF_8));
            } catch (Exception bad) {
                Log.add("Ignored a relay message that wasn't encrypted with this pairing code.");
                return;
            }
            reqId = cmd.optString("id", "?");
            long sentAt = cmd.optLong("t", 0);
            if (sentAt > 0 && Math.abs(System.currentTimeMillis() / 1000 - sentAt) > 300) {
                Log.add("Ignored an expired command.");
                return;
            }
            String name = cmd.getString("name");
            JSONObject input = cmd.optJSONObject("input");
            if (input == null) input = new JSONObject();
            if (svc.agent.isRunning()) {
                reply(reqId, null, "The on-phone agent is running; stop it first.", true);
                return;
            }
            Log.add("Claude (relay): " + name + " " + input);
            svc.showOverlay("Claude is controlling this phone: " + name);
            DeviceTools.Result res = svc.tools.run(name, input);
            reply(reqId, res, null, res.isError);
        } catch (Exception e) {
            try { reply(reqId, null, "Phone error: " + e, true); } catch (Exception ignored) { }
        }
    }

    private void reply(String reqId, DeviceTools.Result res, String errText, boolean isError) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", reqId);
        out.put("is_error", isError);
        if (res != null) out.put("content", res.toContent());
        else out.put("content", new DeviceTools.Result(errText, null, true).toContent());
        publish(resTopic, encrypt(out.toString().getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ relay HTTP

    /** Message text, or the attachment's contents when the relay stored a long message as a file. */
    private static String body(JSONObject ev) throws Exception {
        JSONObject att = ev.optJSONObject("attachment");
        if (att != null && att.has("url")) {
            HttpURLConnection c = (HttpURLConnection) new URL(att.getString("url")).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            InputStream in = c.getInputStream();
            String s = AgentRunner.readAll(in);
            c.disconnect();
            return s.trim();
        }
        return ev.optString("message", "").trim();
    }

    private void publish(String topic, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/" + topic).openConnection();
        c.setRequestMethod("PUT");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        if (data.length > 3500) c.setRequestProperty("Filename", "msg.txt"); // big results travel as a file
        c.setRequestProperty("Cache", "yes");
        auth(c);
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.close();
        int code = c.getResponseCode();
        c.disconnect();
        if (code != 200) throw new Exception("relay publish failed: " + code + (code == 429 ? " (relay message limit reached; add an access token)" : ""));
    }

    private void auth(HttpURLConnection c) {
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
    }

    // ------------------------------------------------------------------ crypto

    private String encrypt(byte[] plain) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return Base64.encodeToString(concat(nonce, c.doFinal(plain)), Base64.NO_WRAP);
    }

    private byte[] decrypt(String b64) throws Exception {
        byte[] all = Base64.decode(b64, Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
        return c.doFinal(all, 12, all.length - 12);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        if (s.length() % 2 != 0) return new byte[0];
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }
}
