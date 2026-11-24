package dev.phonepilot;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * install_apk: installs an APK that is downloaded from a URL or was uploaded over the Wi-Fi bridge
 * (POST /upload). A .zip is opened and the APK inside it is installed; split APKs (base.apk plus
 * split_*.apk, e.g. from an .apks bundle) go in together. The install runs in the background so a
 * slow download never trips the tunnel's time limit: the tool waits up to `wait` seconds and then
 * says how far it got; install_apk check=true asks again.
 *
 * Android asks "Do you want to install this app?" for every install it can't do silently; with
 * confirm=true (default) Phone Pilot taps Install/Update in that system dialog itself. Updates of
 * apps Phone Pilot installed earlier go through without the dialog on Android 12+.
 */
final class Installer {

    static final long MAX_BYTES = 500L * 1024 * 1024;
    private static final String ACTION = "dev.phonepilot.INSTALL_STATUS";
    private static final String[] CONFIRM = {"install", "update", "reinstall"};

    private final PilotService svc;
    private Job job;

    Installer(PilotService svc) { this.svc = svc; }

    /** One install, run on its own thread. */
    static final class Job {
        final long started = SystemClock.uptimeMillis();
        volatile String stage = "Starting";
        volatile String result;          // final message, set once
        volatile boolean error;
        volatile int status = Integer.MIN_VALUE;  // PackageInstaller status from the system
        volatile String statusMsg;
        volatile boolean pendingUser;    // the system asked for the confirm dialog
        volatile String pkg = "";
        synchronized void finish(boolean err, String msg) {
            if (result != null) return;
            error = err;
            result = msg;
            notifyAll();
        }
        synchronized void waitDone(long ms) {
            long end = SystemClock.uptimeMillis() + ms;
            while (result == null) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) return;
                try { wait(left); } catch (InterruptedException e) { return; }
            }
        }
        long secs() { return (SystemClock.uptimeMillis() - started) / 1000; }
    }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "install");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Where POST /upload puts a file; also removes uploads older than a day. */
    static File newUpload(Context c, String name) {
        File d = dir(c);
        File[] old = d.listFiles();
        if (old != null) for (File f : old)
            if (f.getName().startsWith("up-") && System.currentTimeMillis() - f.lastModified() > 86_400_000L) f.delete();
        String base = name == null ? "" : name.replaceAll(".*[/\\\\]", "").replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty() || base.startsWith(".")) base = "file.apk";
        if (base.length() > 80) base = base.substring(base.length() - 80);
        return new File(d, "up-" + System.currentTimeMillis() + "-" + base);
    }

    // ------------------------------------------------------------------ tool entry

    /** Returns {isError, text}. */
    synchronized String[] run(String url, String file, String pick, boolean confirm, double waitSecs, boolean check) {
        Job j = job;
        if (check || (url.isEmpty() && file.isEmpty())) {
            if (j == null) return fail("No install has been started. Pass url (a link to an .apk or .zip) or file "
                    + "(a name from POST /upload).");
            j.waitDone((long) (waitSecs * 1000));
            return report(j);
        }
        if (j != null && j.result == null)
            return fail("An install is already running (" + j.stage + ", " + j.secs() + " s). Call install_apk check=true.");
        if (!svc.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + svc.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                svc.startActivity(s);
            } catch (Exception ignored) { }
            return fail("Phone Pilot isn't allowed to install apps yet. The settings page is open on the phone: ask "
                    + "the user (ask_user) to turn on \"Allow from this source\" for Phone Pilot, then try again.");
        }
        File src = null;
        if (!file.isEmpty()) {
            if (file.contains("/") || file.contains("\\") || file.startsWith(".")) return fail("Bad file name " + file + ".");
            src = new File(dir(svc), file);
            if (!src.isFile()) return fail("No uploaded file called " + file + ". Upload it first (POST /upload).");
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return fail("url must start with http:// or https://.");
        }
        final Job nj = new Job();
        job = nj;
        final File upload = src;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    work(nj, url, upload, pick, confirm);
                } catch (Throwable t) {
                    nj.finish(true, "Install failed: " + t);
                }
                Log.add("install_apk: " + nj.result);
            }
        }, "install-apk").start();
        nj.waitDone((long) (waitSecs * 1000));
        return report(nj);
    }

    private static String[] fail(String m) { return new String[]{"1", m}; }

    private static String[] report(Job j) {
        if (j.result != null) return new String[]{j.error ? "1" : "0", j.result};
        return new String[]{"0", "Still working after " + j.secs() + " s: " + j.stage
                + ". Call install_apk check=true wait=60 for the result."};
    }

    // ------------------------------------------------------------------ the install itself

    private void work(Job j, String url, File upload, String pick, boolean confirm) throws Exception {
        File d = dir(svc);
        List<File> temp = new ArrayList<>();
        try {
            File src = upload;
            String srcName;
            if (src == null) {
                srcName = Uri.parse(url).getLastPathSegment();
                src = new File(d, "dl-" + System.currentTimeMillis());
                temp.add(src);
                String err = download(j, url, src);
                if (err != null) { j.finish(true, err); return; }
            } else {
                srcName = upload.getName().replaceFirst("^up-\\d+-", "");
            }
            if (srcName == null || srcName.isEmpty()) srcName = "the download";

            j.stage = "Looking for the APK";
            List<File> apks = new ArrayList<>();
            String err = findApks(src, srcName, pick, d, apks, temp);
            if (err != null) { j.finish(true, err); return; }

            PackageManager pm = svc.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apks.get(0).getPath(), 0);
            if (info == null) {
                for (File a : apks) {   // with splits, base.apk is the one that has the package details
                    info = pm.getPackageArchiveInfo(a.getPath(), 0);
                    if (info != null && a.getName().contains("base")) break;
                }
            }
            if (info == null) { j.finish(true, "The APK in " + srcName + " couldn't be read (corrupt or not an Android app)."); return; }
            j.pkg = info.packageName;
            String what = info.packageName + " " + (info.versionName == null ? "" : info.versionName)
                    + " (version code " + info.getLongVersionCode() + ")";
            String before = installedVersion(pm, info.packageName);

            j.stage = "Installing " + what;
            commit(j, apks, info.packageName);

            boolean self = info.packageName.equals(svc.getPackageName());
            if (self) j.finish(false, "Updating Phone Pilot itself to " + what + ". The bridge drops while the app "
                    + "restarts; check with get_screen in about 20 s. If the accessibility service is off afterwards, "
                    + "ask the user to turn it back on.");

            long t0 = SystemClock.uptimeMillis();
            long lastTap = 0;
            int taps = 0;
            boolean protect = false;
            while (j.status == Integer.MIN_VALUE && SystemClock.uptimeMillis() - t0 < 180_000) {
                PilotService.sleep(400);
                if (!j.pendingUser) continue;
                String fg = svc.activePackage();
                if (fg.contains("vending") || fg.contains("android.gms")) {   // Play Protect: the user decides
                    if (!protect) Log.add("install_apk: Play Protect is asking; leaving it to the user.");
                    protect = true;
                    j.stage = "Play Protect is showing a dialog on the phone (" + fg + "); ask the user to deal with it";
                    continue;
                }
                if (!confirm) {
                    j.stage = "Waiting for someone to tap Install on the phone (confirm=false)";
                    continue;
                }
                if (taps < 3 && SystemClock.uptimeMillis() - lastTap > 2500) {
                    AccessibilityNodeInfo b = confirmButton();
                    if (b != null) {
                        PilotService.Outcome o = svc.clickNode(b, "the install confirmation");
                        Log.add("install_apk: " + o.msg);
                        lastTap = SystemClock.uptimeMillis();
                        taps++;
                        j.stage = "Tapped Install, waiting for Android to finish";
                    } else {
                        j.stage = "Waiting for the install confirmation dialog";
                    }
                }
            }
            if (self) return;
            if (j.status == Integer.MIN_VALUE) {
                j.finish(true, "No answer from Android after 3 minutes (" + j.stage + "). Look at the screen.");
                return;
            }
            long secs = j.secs();
            if (j.status == PackageInstaller.STATUS_SUCCESS) {
                j.finish(false, (before == null ? "Installed " : "Updated " + before + " -> ") + what + " in " + secs
                        + " s. Open it with launch_app " + info.packageName + ".");
            } else {
                j.finish(true, "Install of " + what + " failed: " + explain(j.status, j.statusMsg));
            }
        } finally {
            for (File f : temp) f.delete();
            // Keep a failed upload so a retry (e.g. with apk=) doesn't need it sent again.
            if (upload != null && j.result != null && !j.error) upload.delete();
        }
    }

    private static String installedVersion(PackageManager pm, String pkg) {
        try {
            PackageInfo p = pm.getPackageInfo(pkg, 0);
            return (p.versionName == null ? "" : p.versionName + " ") + "(version code " + p.getLongVersionCode() + ")";
        } catch (Exception e) {
            return null;   // not installed (or not visible to Phone Pilot)
        }
    }

    /** Download url into dest. Returns an error message, or null. */
    private static String download(Job j, String url, File dest) throws IOException {
        j.stage = "Downloading";
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            for (int hop = 0; ; hop++) {   // follow redirects, including http <-> https
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "PhonePilot");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null && hop < 8) {
                    u = new URL(u, c.getHeaderField("Location"));
                    c.disconnect();
                    continue;
                }
                if (code != 200) return "Download failed: the server answered HTTP " + code
                        + (code == 401 || code == 403 || code == 404 ? " (private link, sign-in needed, or wrong URL)" : "") + ".";
                break;
            }
            long total = c.getContentLengthLong();
            if (total > MAX_BYTES) return "The file is " + mb(total) + ", over the " + mb(MAX_BYTES) + " limit.";
            long got = 0;
            byte[] buf = new byte[65536];
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (got > MAX_BYTES) return "The download passed the " + mb(MAX_BYTES) + " limit; stopped.";
                    j.stage = "Downloading: " + mb(got) + (total > 0 ? " of " + mb(total) : "");
                }
            }
            if (total > 0 && got < total) return "The download stopped early (" + mb(got) + " of " + mb(total) + ").";
            Log.add("install_apk: downloaded " + mb(got) + " from " + u.getHost());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String mb(long b) {
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    /**
     * src is an APK, or a zip holding one (or base + split APKs). Fills apks; returns an error or null.
     */
    private static String findApks(File src, String srcName, String pick, File d, List<File> apks, List<File> temp)
            throws IOException {
        ZipFile z;
        try {
            z = new ZipFile(src);
        } catch (ZipException e) {
            return "No APK file found: " + srcName + " is neither an APK nor a zip file"
                    + (looksLikeHtml(src) ? " (it's a web page; the link may need a sign-in or point to a page, not the file)" : "") + ".";
        }
        try {
            if (z.getEntry("AndroidManifest.xml") != null) {   // it's an APK itself
                apks.add(src);
                return null;
            }
            List<ZipEntry> found = new ArrayList<>();
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory()) continue;
                names.add(n);
                String base = n.substring(n.lastIndexOf('/') + 1);
                if (n.startsWith("__MACOSX/") || base.startsWith("._")) continue;
                if (base.toLowerCase(Locale.US).endsWith(".apk")) found.add(e);
            }
            if (!pick.isEmpty()) {
                List<ZipEntry> f = new ArrayList<>();
                for (ZipEntry e : found) if (e.getName().toLowerCase(Locale.US).contains(pick.toLowerCase(Locale.US))) f.add(e);
                if (f.isEmpty() && !found.isEmpty())
                    return "No APK matching \"" + pick + "\" in " + srcName + ". APKs in it: " + list(found) + ".";
                found = f;
            }
            if (found.isEmpty()) {
                return "No APK file found in " + srcName + ". It holds " + names.size() + " file(s)"
                        + (names.isEmpty() ? "" : ": " + join(names, 15)) + ".";
            }
            if (found.size() > 1 && !areSplits(found)) {
                return "Found " + found.size() + " APKs in " + srcName + ": " + list(found)
                        + ". Pass apk=<part of the name> to pick one.";
            }
            for (ZipEntry e : found) {
                if (e.getSize() > MAX_BYTES) return e.getName() + " is over the " + mb(MAX_BYTES) + " limit.";
                File out = new File(d, "x-" + System.nanoTime() + ".apk");
                temp.add(out);
                try (InputStream in = z.getInputStream(e); OutputStream o = new FileOutputStream(out)) {
                    copy(in, o);
                }
                apks.add(out);
            }
            return null;
        } finally {
            z.close();
        }
    }

    /** base.apk plus split_*.apk (an app bundle's splits) install together. */
    private static boolean areSplits(List<ZipEntry> es) {
        boolean base = false;
        for (ZipEntry e : es) {
            String n = e.getName().substring(e.getName().lastIndexOf('/') + 1).toLowerCase(Locale.US);
            if (n.equals("base.apk") || n.startsWith("base-")) base = true;
            else if (!n.startsWith("split")) return false;
        }
        return base;
    }

    private static boolean looksLikeHtml(File f) {
        byte[] b = new byte[256];
        try (InputStream in = new FileInputStream(f)) {
            int n = in.read(b);
            String s = n > 0 ? new String(b, 0, n, "UTF-8").trim().toLowerCase(Locale.US) : "";
            return s.startsWith("<!doctype html") || s.startsWith("<html") || s.startsWith("<");
        } catch (IOException e) {
            return false;
        }
    }

    private static String list(List<ZipEntry> es) {
        List<String> n = new ArrayList<>();
        for (ZipEntry e : es) n.add(e.getName());
        return join(n, 15);
    }

    private static String join(List<String> s, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.size() && i < max; i++) b.append(i == 0 ? "" : ", ").append(s.get(i));
        if (s.size() > max) b.append(", … ").append(s.size() - max).append(" more");
        return b.toString();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private void commit(final Job j, List<File> apks, String pkg) throws IOException {
        PackageInstaller pi = svc.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(pkg);
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        final int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            int i = 0;
            for (File a : apks) {
                try (InputStream in = new FileInputStream(a); OutputStream out = s.openWrite("apk" + (i++) + ".apk", 0, a.length())) {
                    copy(in, out);
                    s.fsync(out);
                }
            }
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent in) {
                    if (in.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return;
                    int st = in.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = in.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try { svc.startActivity(confirm); } catch (Exception e) { Log.add("install_apk: " + e); }
                        }
                        j.pendingUser = true;
                        return;
                    }
                    j.statusMsg = in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    j.status = st;
                    try { svc.unregisterReceiver(this); } catch (Exception ignored) { }
                }
            };
            IntentFilter f = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) svc.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            else svc.registerReceiver(r, f);
            Intent cb = new Intent(ACTION).setPackage(svc.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(svc, id, cb, flags);
            s.commit(pend.getIntentSender());
        } catch (IOException | RuntimeException e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

    /** The Install / Update button of the system's confirmation dialog, if it is on screen. */
    private AccessibilityNodeInfo confirmButton() {
        for (String t : CONFIRM) {
            for (AccessibilityNodeInfo n : svc.findNodes(t, "", true)) if (installerNode(n) && n.isEnabled()) return n;
        }
        // Other languages: the dialog's positive button.
        for (AccessibilityNodeInfo n : svc.findNodes("", "android:id/button1", false)) if (installerNode(n) && n.isEnabled()) return n;
        return null;
    }

    private static boolean installerNode(AccessibilityNodeInfo n) {
        CharSequence p = n.getPackageName();
        return p != null && p.toString().contains("packageinstaller");
    }

    private static String explain(int status, String msg) {
        String m = msg == null || msg.isEmpty() ? "" : " [" + msg + "]";
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "cancelled (Cancel was tapped or the dialog was closed)." + m;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "blocked by Play Protect or a device policy." + m;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "it conflicts with the app already installed, usually because that one is signed with a "
                        + "different key or has a higher version code. The user must uninstall the old app first." + m;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "not compatible with this phone (Android version or CPU type)." + m;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "the APK is invalid (corrupt, unsigned, or a split APK without its base)." + m;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "not enough storage on the phone." + m;
            default:
                return "Android reported status " + status + "." + m;
        }
    }
}
