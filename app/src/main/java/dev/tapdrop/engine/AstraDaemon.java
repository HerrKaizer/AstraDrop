package dev.tapdrop.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AstraDrop's SolEngine: a small shell-user (uid 2000) process started once over Wireless
 * debugging. It switches NFC, which normal apps can't, and turns it back off if AstraDrop dies.
 *
 * Lives side by side with SolCube's engine: its own process name ("astradrop_engine"), its own port
 * (127.0.0.1:47331), its own secret. It never touches other processes or Wireless debugging.
 *
 * Protocol (one command per connection): engine sends a random nonce line; the app answers
 * "COMMAND args HMAC" where HMAC = HmacSHA256(secret, nonce + " " + "COMMAND args"); engine replies
 * one line. Commands: PING, NFC 1|0, STATE, HOTSPOT 1|0, HOTINFO, WATCH pid solcube(0|1), UNWATCH,
 * A11Y component, PILL text, SHUTDOWN.
 *
 * The hotspot is the user's own (named after the phone, remembered by the iPhone), switched through
 * the tethering service with the shell's TETHER_PRIVILEGED permission. Like NFC, it goes back off
 * when AstraDrop closes, but only if the engine was the one that turned it on.
 */
public final class AstraDaemon {
    public static final int PORT = 47331;
    public static final String NAME = "astradrop_engine";
    private static final String TAG = "AstraDrop.eng";
    private static final String PKG = "dev.tapdrop";

    private final String secret;
    private final String apk;
    private final int version;
    private volatile boolean stop;
    private volatile int watchPid;
    private volatile boolean watchSolCube;
    /** We switched the hotspot on: switch it off again when AstraDrop goes. */
    private volatile boolean hotOwned;
    private ServerSocket server;

    private AstraDaemon(String secret, String apk) {
        this.secret = secret;
        this.apk = apk;
        this.version = versionOf(apk);
    }

    /** args: secret, apk path. Must match Engine.versionOf in the app. */
    public static int versionOf(String apk) { return apk.hashCode() & 0x7fffffff; }

    public static void main(String[] args) {
        if (args.length < 2) System.exit(2);
        AstraDaemon d = new AstraDaemon(args[0], args[1]);
        String ping = ask(d.secret, "PING");
        if (ping != null && ping.equals("ok " + d.version)) { log("already running"); System.exit(0); }
        if (ping != null) {
            ask(d.secret, "SHUTDOWN");
            long until = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < until && ask(d.secret, "PING") != null) sleep(100);
        }
        killStale();
        try {
            d.server = new ServerSocket();
            d.server.setReuseAddress(true);
            d.server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
        } catch (Exception e) {
            log("port busy: " + e);
            System.exit(3);
        }
        // lets the app keep its pairing alive and switch Wireless debugging on for the next start
        exec("pm", "grant", PKG, "android.permission.WRITE_SECURE_SETTINGS");
        d.startWatchers();
        log("engine v" + d.version + " up");
        d.serve();
        System.exit(0);
    }

    // ------------------------------------------------------------------ server

    private void serve() {
        while (!stop) {
            final Socket s;
            try { s = server.accept(); } catch (Exception e) { if (stop) break; continue; }
            Thread t = new Thread(() -> handle(s), "conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            OutputStream out = sock.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String nonce = nonce();
            out.write((nonce + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line = in.readLine();
            if (line == null) return;
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) return;
            String cmd = line.substring(0, sp);
            String mac = line.substring(sp + 1);
            if (!MessageDigest.isEqual(mac.getBytes(StandardCharsets.UTF_8),
                    mac(secret, nonce + " " + cmd).getBytes(StandardCharsets.UTF_8))) {
                out.write("denied\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String reply = run(cmd);
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (stop) { try { server.close(); } catch (Exception ignored) {} }
        } catch (Exception ignored) {
        }
    }

    private String run(String cmd) {
        String[] p = cmd.split(" ", 2);
        String arg = p.length > 1 ? p[1] : "";
        switch (p[0]) {
            case "PING": return "ok " + version;
            case "NFC": return "ok " + setNfc(arg.equals("1"));
            case "STATE": return "ok " + nfcState();
            case "HOTSPOT": return "ok " + setHotspot(arg.equals("1"));
            case "HOTINFO": return "ok " + hotInfo();
            case "WIFINAME": return "ok " + wifiName();
            case "WATCH": {
                String[] a = arg.split(" ");
                try { watchPid = Integer.parseInt(a[0]); } catch (Exception e) { return "err"; }
                watchSolCube = a.length > 1 && a[1].equals("1");
                return "ok";
            }
            case "UNWATCH": watchPid = 0; return "ok";
            case "A11Y": {
                String cur = execOut("settings", "get", "secure", "enabled_accessibility_services").trim();
                if (cur.equals("null")) cur = "";
                if (!(":" + cur + ":").contains(":" + arg + ":")) {
                    exec("settings", "put", "secure", "enabled_accessibility_services", cur.isEmpty() ? arg : cur + ":" + arg);
                    exec("settings", "put", "secure", "accessibility_enabled", "1");
                }
                return "ok";
            }
            case "PILL": solCubePill(arg); return "ok";
            case "SHUTDOWN": stop = true; return "ok";
            default: return "err unknown";
        }
    }

    // ------------------------------------------------------------------ watchers

    private void startWatchers() {
        Thread w = new Thread(() -> {
            while (!stop) {
                sleep(400);
                int pid = watchPid;
                if (pid > 0 && !new File("/proc/" + pid).exists()) {
                    watchPid = 0;
                    log("AstraDrop gone, NFC off");
                    if (setNfc(false).equals("switched") && watchSolCube) solCubePill("NFC off");
                    if (hotOwned) { log("hotspot off"); setHotspot(false); }
                }
            }
        }, "watch");
        w.setDaemon(true);
        w.start();
        // AstraDrop updated (its APK path changes) or uninstalled: start the new engine, or just end
        Thread u = new Thread(() -> {
            while (!stop) {
                sleep(5000);
                if (new File(apk).exists()) continue;
                sleep(1500);   // let the install finish
                String now = execOut("pm", "path", PKG).trim();
                if (now.startsWith("package:")) {
                    String path = now.split("\n")[0].substring(8).trim();
                    log("updated, restarting from " + path);
                    exec("sh", "-c", "CLASSPATH='" + path + "' setsid app_process /system/bin --nice-name=" + NAME
                        + " " + AstraDaemon.class.getName() + " " + secret + " '" + path + "' </dev/null >/dev/null 2>&1 &");
                } else {
                    log("uninstalled, exiting");
                }
                // the new engine asks us to SHUTDOWN; exit anyway if it doesn't
                sleep(8000);
                System.exit(0);
            }
        }, "apk");
        u.setDaemon(true);
        u.start();
    }

    /** Leftovers of an older AstraDrop engine (only ours, by name; never anything else). */
    private static void killStale() {
        int me = android.os.Process.myPid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File f : procs) {
            int pid;
            try { pid = Integer.parseInt(f.getName()); } catch (NumberFormatException e) { continue; }
            if (pid == me) continue;
            String cmd = readFile(new File(f, "cmdline"));
            if (cmd.startsWith(NAME + "\0") || cmd.equals(NAME)) {
                android.os.Process.killProcess(pid);
                log("killed stale engine " + pid);
            }
        }
    }

    // ------------------------------------------------------------------ NFC

    /** "switched", "same" or "failed". */
    private synchronized String setNfc(boolean on) {
        String want = on ? "on" : "off";
        if (settled(3000).equals(want)) return "same";
        // vivo's own Settings switch first (keeps its UI in sync), then the standard one
        if (!execOut("getprop", "ro.vivo.os.version").trim().isEmpty()) {
            for (int i = 0; i < 2; i++) {
                String out = execOut("cmd", "app_function", "execute-app-function", "--package", "com.android.settings",
                    "--function", "setDeviceStateItem", "--parameters",
                    "{\"setDeviceStateItemParams\":{\"key\":\"vivo_nfc_settings_screen/nfc_toggle\",\"value\":\"" + on
                        + "\",\"requestInitiatedWhileUnlocked\":true}}");
                if (!isAppFunctionSuccess(out)) break;
                if (settled(5000).equals(want)) return "switched";
            }
        }
        exec("svc", "nfc", on ? "enable" : "disable");
        return settled(5000).equals(want) ? "switched" : "failed";
    }

    private static String nfcState() { return parseNfcState(execOut("dumpsys", "nfc")); }

    private static String settled(long ms) {
        long end = System.currentTimeMillis() + ms;
        String s = nfcState();
        while (!s.equals("on") && !s.equals("off") && System.currentTimeMillis() < end) {
            sleep(150);
            s = nfcState();
        }
        return s;
    }

    /** The raw `mState=` value from `dumpsys nfc` (`on`, `off`, `turning on`...), or "". */
    public static String parseNfcState(String dump) {
        if (dump == null) return "";
        for (String line : dump.split("\n")) {
            int i = line.indexOf("mState=");
            if (i >= 0) return line.substring(i + 7).trim();
        }
        return "";
    }

    /** True when `cmd app_function` output reports `"isSuccessful": [true]`. */
    public static boolean isAppFunctionSuccess(String out) {
        return out != null && out.replaceAll("\\s+", "").contains("\"isSuccessful\":[true]");
    }

    /** SolCube draws the same pill and its accessibility service is usually on already. */
    private static void solCubePill(String text) {
        exec("am", "broadcast", "-n", "dev.suite/.IslandReceiver", "--es", "text", text, "--ez", "ok", "true");
    }

    /** base64 of the connected Wi-Fi's name ("" when not connected). Apps need location access for it; the shell doesn't. */
    private static String wifiName() {
        return java.util.Base64.getEncoder().encodeToString(parseWifiName(execOut("cmd", "wifi", "status")).getBytes(StandardCharsets.UTF_8));
    }

    /** From `cmd wifi status`: the name in `Wifi is connected to "Name"`, or "". */
    public static String parseWifiName(String status) {
        if (status == null) return "";
        for (String line : status.split("\n")) {
            String l = line.trim();
            if (l.startsWith("Wifi is connected to \"") && l.endsWith("\"")) return l.substring(22, l.length() - 1);
        }
        return "";
    }

    // ------------------------------------------------------------------ hotspot

    private static final int AP_ENABLED = 13, AP_ENABLING = 12;

    private static Object service(String name, String stub) throws Exception {
        Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        return Class.forName(stub).getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
    }

    private static java.lang.reflect.Method method(Object o, String name) {
        for (java.lang.reflect.Method m : o.getClass().getMethods()) if (m.getName().equals(name)) return m;
        throw new IllegalStateException("no " + name);
    }

    private static int apState() {
        try {
            Object wifi = service("wifi", "android.net.wifi.IWifiManager$Stub");
            return (Integer) method(wifi, "getWifiApEnabledState").invoke(wifi);
        } catch (Throwable t) { return -1; }
    }

    /** "on|off sec base64(ssid) base64(pass)" of the user's own hotspot (sec: 0 open, 1 WPA2, 2+ WPA3...). */
    private static String hotInfo() {
        try {
            Object wifi = service("wifi", "android.net.wifi.IWifiManager$Stub");
            Object c = method(wifi, "getSoftApConfiguration").invoke(wifi);
            Object ssid = c.getClass().getMethod("getSsid").invoke(c);
            Object pass = c.getClass().getMethod("getPassphrase").invoke(c);
            int sec = (Integer) c.getClass().getMethod("getSecurityType").invoke(c);
            java.util.Base64.Encoder e = java.util.Base64.getEncoder();
            return (apState() == AP_ENABLED ? "on" : "off") + " " + sec + " "
                + e.encodeToString(String.valueOf(ssid == null ? "" : ssid).getBytes(StandardCharsets.UTF_8)) + " "
                + e.encodeToString(String.valueOf(pass == null ? "" : pass).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            log("hotinfo: " + t);
            return "err";
        }
    }

    /** "switched", "same" or "failed why" (why = the tethering service's error code, or the exception). */
    private synchronized String setHotspot(boolean on) {
        int st = apState();
        if (on ? st == AP_ENABLED : (st != AP_ENABLED && st != AP_ENABLING)) {
            if (!on) hotOwned = false;
            return "same";
        }
        try {
            Object conn = service("tethering", "android.net.ITetheringConnector$Stub");
            final Object lock = new Object();
            final int[] res = {-99};
            final android.os.Binder cb = new android.os.Binder() {
                @Override protected boolean onTransact(int code, android.os.Parcel d, android.os.Parcel r, int flags) {
                    if (code != android.os.IBinder.FIRST_CALL_TRANSACTION) return false;
                    d.enforceInterface("android.net.IIntResultListener");
                    synchronized (lock) { res[0] = d.readInt(); lock.notifyAll(); }
                    return true;
                }
            };
            Class<?> lc = Class.forName("android.net.IIntResultListener");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(lc.getClassLoader(), new Class<?>[]{lc},
                (p, m, a) -> m.getName().equals("asBinder") ? cb : null);
            if (on) {
                Class<?> rc = Class.forName("android.net.TetheringRequestParcel");
                Object req = rc.getDeclaredConstructor().newInstance();
                rc.getField("tetheringType").setInt(req, 0);   // TETHERING_WIFI
                rc.getField("showProvisioningUi").setBoolean(req, true);
                // without the global scope the hotspot comes up but never gets an address (tested on
                // Android 16): nothing could reach AstraDrop over it
                try { rc.getField("connectivityScope").setInt(req, 1); } catch (Throwable ignored) {}
                try { rc.getField("packageName").set(req, "com.android.shell"); } catch (Throwable ignored) {}
                try { rc.getField("uid").setInt(req, android.os.Process.myUid()); } catch (Throwable ignored) {}
                method(conn, "startTethering").invoke(conn, req, "com.android.shell", null, listener);
            } else {
                method(conn, "stopTethering").invoke(conn, 0, "com.android.shell", null, listener);
            }
            synchronized (lock) { if (res[0] == -99) lock.wait(5000); }
            if (res[0] != 0) return "failed " + res[0];
            long end = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < end && (apState() == AP_ENABLED) != on) sleep(150);
            boolean ok = (apState() == AP_ENABLED) == on;
            if (ok) hotOwned = on;
            return ok ? "switched" : "failed timeout";
        } catch (Throwable t) {
            log("hotspot: " + t);
            return "failed " + t.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Asks an engine already on the port (null if none answers). */
    private static String ask(String secret, String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            s.setSoTimeout(1500);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String nonce = in.readLine();
            if (nonce == null) return null;
            s.getOutputStream().write((cmd + " " + mac(secret, nonce + " " + cmd) + "\n").getBytes(StandardCharsets.UTF_8));
            return in.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    public static String mac(String secret, String msg) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : m.doFinal(msg.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String nonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void exec(String... cmd) { execOut(cmd); }

    private static String execOut(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readFile(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[256];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private static void log(String m) { exec("log", "-t", TAG, m); }
}
