package dev.phonepilot;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wi-Fi bridge: a tiny HTTP server on the phone so Claude on your computer (Claude Code or
 * Claude Desktop, through phone_app_mcp.py) can use the same phone tools without USB/ADB.
 *
 *   GET  /tools                      -> {"tools": [...]}   tool definitions
 *   POST /call  {"name","input"}     -> {"content": [...], "is_error": bool}
 *
 * Every request must carry "Authorization: Bearer <token>" (token shown in the app).
 */
public final class RemoteServer {

    public static final int PORT = 8765;

    private final PilotService svc;
    private volatile ServerSocket server;
    private volatile String token;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    RemoteServer(PilotService svc) { this.svc = svc; }

    public boolean isRunning() { return server != null; }

    public synchronized void start(String token) throws IOException {
        if (server != null) return;
        this.token = token;
        final ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(PORT));
        server = ss;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (!ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        pool.execute(new Runnable() { @Override public void run() { handle(s); } });
                    } catch (IOException e) {
                        break;
                    }
                }
            }
        }, "wifi-bridge");
        t.start();
        Log.add("Wi-Fi bridge listening on port " + PORT);
    }

    public synchronized void stop() {
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) { }
            Log.add("Wi-Fi bridge stopped.");
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) { s.close(); return; }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            int len = 0;
            try { len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0"); }
            catch (NumberFormatException ignored) { }
            if (len > 1_000_000) { respond(s, 413, "{\"error\":\"too large\"}"); return; }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }

            String auth = headers.get("authorization");
            if (token == null || auth == null || !constantTimeEquals(auth, "Bearer " + token)) {
                respond(s, 401, "{\"error\":\"missing or wrong token\"}");
                return;
            }

            if ("GET".equals(method) && path.startsWith("/tools")) {
                respond(s, 200, new JSONObject().put("tools", DeviceTools.definitions()).toString());
            } else if ("POST".equals(method) && path.startsWith("/call")) {
                JSONObject req = new JSONObject(new String(body, 0, off, StandardCharsets.UTF_8));
                String name = req.getString("name");
                JSONObject input = req.optJSONObject("input");
                if (input == null) input = new JSONObject();
                if (svc.agent.isRunning()) {
                    respond(s, 409, "{\"error\":\"the on-phone agent is running; stop it first\"}");
                    return;
                }
                Log.add("Remote: " + name + " " + input);
                svc.keepAwake();
                String problem = svc.screenProblem();
                boolean readOnly = name.equals("get_screen") || name.equals("screenshot")
                        || name.equals("list_apps") || name.equals("wait")
                        || name.equals("wait_for") || name.equals("wait_gone");
                DeviceTools.Result r = (problem != null && !readOnly)
                        ? new DeviceTools.Result(problem + " Ask the user to unlock it; nothing was done.", null, true)
                        : svc.tools.run(name, input);
                if (problem != null && readOnly && !r.isError)
                    r = new DeviceTools.Result("[" + problem + "]\n" + r.text, r.jpeg, false);
                JSONObject out = new JSONObject();
                out.put("content", r.toContent());
                out.put("is_error", r.isError);
                respond(s, 200, out.toString());
            } else if ("GET".equals(method) && path.startsWith("/health")) {
                respond(s, 200, "{\"ok\":true}");
            } else {
                respond(s, 404, "{\"error\":\"not found\"}");
            }
        } catch (Exception e) {
            try { respond(s, 500, new JSONObject().put("error", e.toString()).toString()); }
            catch (Exception ignored) { }
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(Socket s, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
