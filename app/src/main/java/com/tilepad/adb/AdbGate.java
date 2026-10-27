package com.tilepad.adb;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Root daemon started by the NEMESIS service (svc/adbgated.sh) through app_process. It is the
 * "allow every computer in" half of the ADB tile.
 *
 * While the tile is on, adbd is moved to an internal port and the daemon listens on the public
 * port, relaying each computer to adbd. Every computer is let in (that is the tile's point), but
 * each one's key is identified and logged, and a new key is saved to {@code STATE_DIR/keys},
 * which outlives turning the tile off. How depends on adbd ({@link AdbAuth}):
 *
 * <ul>
 * <li>adbd that doesn't check keys over TCP, whatever {@code ro.adb.secure} says (the unit this
 *     was developed on): it would let anything in without a trace, so the relay runs the key
 *     handshake with the computer itself.
 * <li>adbd that checks keys: there is no framework dialog to approve new ones, so the relay
 *     passes the handshake through and, when a computer offers a new key, adds it to
 *     {@code adb_keys}, restarts adbd, and drops the connection. The computer's next
 *     {@code adb connect} is then authorized.
 * </ul>
 *
 * Only Wi-Fi is let in. The relay refuses connections that arrive on any other interface (the
 * telematics modem's cellular links are up too), and a firewall chain keeps everything but
 * loopback off adbd's internal port, which adbd binds on every interface.
 *
 * Off means off: turning the tile off removes exactly the keys the daemon added and stops adbd,
 * so nothing listens for ADB over TCP, and clears {@code persist.service.adb.tcp.enable} so the
 * firmware's init trigger doesn't start adbd on port 5555 again at boot. Turning it on never
 * sets that property (its trigger would race the move to the internal port); after a reboot the
 * daemon brings the relay back itself, from the flag file.
 *
 * Off is applied only when asked for (the tile's request file), never at startup: installing
 * Tilepad over ADB must not cut that ADB connection. While the relay is off the daemon reports
 * whether adbd is listening anyway (turned on by something else, e.g. that persist property),
 * as state "keys": on, but only for computers whose keys were approved before.
 *
 * While on, it also keeps the Wi-Fi client up, turning off the CarPlay hotspot if it has to
 * ({@link AdbWifi}); off turns the client back off if ADB is what turned it on.
 *
 * It is idle until the tile creates the flag file, which it polls for. adbd control (setprop,
 * ctl.start/ctl.stop) needs root; writing adb_keys needs uid 1000, so that one step is delegated
 * to adb-keys.sh run as uid 1000 through {@code nemesis root 1000 -c}.
 *
 * Usage: AdbGate PUBLIC_PORT ADBD_PORT STATE_DIR NEMESIS KEYS_HELPER
 */
public final class AdbGate {
    private static final long POLL_MS = 1000;
    private static final long ERROR_RETRY_MS = 10000;
    private static final long FIREWALL_CHECK_MS = 30000;
    private static final int HANDSHAKE_TIMEOUT_MS = 15000;
    private static final int BUFFER = 16384;
    /** The iptables chain (jumped to from INPUT) that guards the ports while the relay runs. */
    private static final String CHAIN = "tilepad_adb";

    private final int publicPort;
    private final int adbdPort;
    private final File stateDir;
    private final File flagFile;
    private final File offRequest;
    private final File statusFile;
    private final File pendingKey;
    /** Every key the relay has seen, as adb_keys lines; kept across off (survives updates). */
    private final File keysFile;
    /** Exists while the Wi-Fi client is on because ADB turned it on (survives reboots). */
    private final File wifiMark;
    private final AdbWifi wifi = new AdbWifi(new AdbWifi.Log() {
        @Override public void log(String msg) { AdbGate.log(msg); }
    });
    private final String nemesis;
    private final String keysHelper;

    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
    /** keysFile, parsed. Guarded by itself. */
    private final List<AdbAuth.Key> keys = new ArrayList<AdbAuth.Key>();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean running;
    private volatile String state = "off";
    private volatile String detail = "";
    private volatile int allowed;
    /** adbd's TCP port while it listens without the relay (state "keys"), else 0. */
    private volatile int keysPort;
    private ServerSocket listener;
    private Thread acceptThread;
    private Method systemPropertiesGet;
    private long errorAt;
    private long firewallAt;

    private AdbGate(int publicPort, int adbdPort, String stateDir, String nemesis, String keysHelper) {
        this.publicPort = publicPort;
        this.adbdPort = adbdPort;
        this.stateDir = new File(stateDir);
        this.flagFile = new File(stateDir, "relay.on");
        this.offRequest = new File(stateDir, "relay.off");
        this.statusFile = new File(stateDir, "status");
        this.pendingKey = new File(stateDir, "pending.key");
        this.keysFile = new File(stateDir, "keys");
        this.wifiMark = new File(stateDir, "wifi.adb");
        this.nemesis = nemesis;
        this.keysHelper = keysHelper;
    }

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("usage: AdbGate PUBLIC_PORT ADBD_PORT STATE_DIR NEMESIS KEYS_HELPER");
            System.exit(2);
        }
        new AdbGate(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], args[3], args[4]).run();
    }

    private void run() {
        log("start public=" + publicPort + " adbd=" + adbdPort + " dir=" + stateDir);
        stateDir.mkdirs();
        loadKeys();
        writeStatus();
        while (true) {
            boolean want = flagFile.exists();
            try {
                if (want && !running) {
                    if (state.equals("error") && System.currentTimeMillis() - errorAt < ERROR_RETRY_MS) {
                        // back off before retrying a failed start
                    } else {
                        startRelay();
                    }
                } else if (!want && (running || offRequest.exists() || state.equals("error"))) {
                    turnOff();
                } else if (!running) {
                    observeAdbd();
                }
                if (running && wifi.keepClientOn()) wifiMark.createNewFile();
                if (running && System.currentTimeMillis() - firewallAt > FIREWALL_CHECK_MS) firewallOn();
            } catch (Exception e) {
                fail("relay error: " + message(e));
                safeStop();
            }
            writeStatus();
            sleep(POLL_MS);
        }
    }

    // ---- on / off ----

    private void startRelay() throws IOException {
        state = "starting";
        detail = "";
        writeStatus();
        log("turning on");
        // Firewall first: adbd binds the internal port on every interface, and doesn't check keys.
        firewallOn();
        // Free the public port: point adbd at the internal port and restart it there.
        restartAdbd(adbdPort);
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress((InetAddress) null, publicPort));
        listener = server;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { accept(); }
        }, "adbgate-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        state = "on";
        detail = "";
        log("on, listening on " + publicPort + ", adbd on " + adbdPort);
    }

    private void turnOff() {
        state = "stopping";
        writeStatus();
        log("turning off");
        safeStop();
        // Remove exactly the keys we added to adb_keys (the relay's own list stays), then stop
        // adbd so nothing listens for ADB at all.
        helper("clear");
        try {
            stopAdbd();
        } catch (IOException e) {
            log("warning: could not stop adbd: " + message(e));
        }
        firewallOff();
        pendingKey.delete();
        if (wifiMark.exists()) {
            wifi.clientOff();
            wifiMark.delete();
        }
        offRequest.delete();
        keysPort = 0;
        state = "off";
        detail = "";
        log("off");
    }

    private void safeStop() {
        running = false;
        ServerSocket server = listener;
        listener = null;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        synchronized (clients) {
            for (Socket s : clients) try { s.close(); } catch (IOException ignored) { }
            clients.clear();
        }
    }

    // ---- relay ----

    private void accept() {
        ServerSocket server = listener;
        while (running && server != null && !server.isClosed()) {
            final Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                if (running) log("accept ended: " + message(e));
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override public void run() { serve(client); }
            }, "adbgate-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket client) {
        clients.add(client);
        Socket upstream = null;
        String who = client.getInetAddress().getHostAddress();
        try {
            if (!onWifi(client)) {
                log(who + ": refused, not on Wi-Fi");
                return;
            }
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataInputStream fromClient = new DataInputStream(client.getInputStream());
            OutputStream toClient = client.getOutputStream();
            byte[] connect = AdbAuth.read(fromClient);
            if (AdbAuth.le(connect, 0) != AdbAuth.A_CNXN) throw new IOException("expected CNXN");
            upstream = new Socket();
            upstream.connect(new InetSocketAddress("127.0.0.1", adbdPort), 5000);
            upstream.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataInputStream fromAdbd = new DataInputStream(upstream.getInputStream());
            OutputStream toAdbd = upstream.getOutputStream();
            toAdbd.write(connect);
            toAdbd.flush();
            // adbd's answer says whether it checks keys itself (AUTH) or lets anyone in (CNXN).
            // This unit's adbd serves one computer at a time and doesn't answer the next one.
            byte[] first;
            try {
                first = AdbAuth.read(fromAdbd);
            } catch (SocketTimeoutException e) {
                throw new IOException("adbd didn't answer (it serves one computer at a time; is another connected?)");
            }
            if (AdbAuth.le(first, 0) == AdbAuth.A_CNXN) {
                AdbAuth.Admission in = AdbAuth.challenge(fromClient, toClient, connect, knownKeys(), random);
                boolean isNew = remember(in.key);
                log(who + ": " + describe(in.key) + (in.signed ? " signed in with a saved key"
                    : isNew ? " new key, saved" : " offered a saved key without signing"));
                toClient.write(first);
                toClient.flush();
            } else {
                AdbAuth.Checked c = AdbAuth.passThrough(fromClient, toClient, fromAdbd, toAdbd, first, knownKeys());
                if (c.offered != null) {
                    offered(who, c.offered);
                    return;
                }
                if (!c.connected) throw new IOException("adbd did not let it in");
                log(who + ": " + (c.signer != null ? describe(c.signer) : "a key adbd knows from before")
                    + " signed in (adbd checked the key)");
            }
            client.setSoTimeout(0);
            upstream.setSoTimeout(0);
            final Socket up = upstream;
            final Socket cl = client;
            Thread down = new Thread(new Runnable() {
                @Override public void run() { pump(up, cl); }
            }, "adbgate-down");
            down.setDaemon(true);
            down.start();
            pump(client, upstream);
        } catch (IOException e) {
            log(who + ": " + message(e));
        } finally {
            close(upstream);
            close(client);
            clients.remove(client);
        }
    }

    /** Copies in -> out until either side closes. */
    private void pump(Socket in, Socket out) {
        byte[] buf = new byte[BUFFER];
        try {
            InputStream is = in.getInputStream();
            OutputStream os = out.getOutputStream();
            for (int n; (n = is.read(buf)) > 0; ) {
                os.write(buf, 0, n);
                os.flush();
            }
        } catch (IOException ignored) {
            // peer closed; the finally in serve() tears the pair down
        } finally {
            close(in);
            close(out);
        }
    }

    /** Only connections that came in on Wi-Fi (or loopback) are relayed. */
    private static boolean onWifi(Socket client) {
        try {
            NetworkInterface nif = NetworkInterface.getByInetAddress(client.getLocalAddress());
            return nif != null && (nif.isLoopback() || nif.getName().startsWith("wlan"));
        } catch (IOException e) {
            return false;
        }
    }

    // ---- keys ----

    private void loadKeys() {
        List<AdbAuth.Key> loaded = new ArrayList<AdbAuth.Key>();
        if (keysFile.exists()) {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(keysFile), "UTF-8"));
                try {
                    for (String line; (line = r.readLine()) != null; ) {
                        AdbAuth.Key k = line.trim().isEmpty() ? null : AdbAuth.Key.parse(line);
                        if (k != null) loaded.add(k);
                    }
                } finally {
                    r.close();
                }
            } catch (IOException e) {
                log("could not read " + keysFile + ": " + message(e));
            }
        }
        synchronized (keys) {
            keys.clear();
            keys.addAll(loaded);
            allowed = keys.size();
        }
        log(loaded.size() + " saved key(s)");
    }

    private List<AdbAuth.Key> knownKeys() {
        synchronized (keys) { return new ArrayList<AdbAuth.Key>(keys); }
    }

    private static String describe(AdbAuth.Key key) {
        return key.comment() + " [" + key.fingerprint() + "]";
    }

    /** Saves key if it hasn't been seen before; returns whether it was new. */
    private boolean remember(AdbAuth.Key key) {
        boolean isNew = true;
        synchronized (keys) {
            for (AdbAuth.Key k : keys) if (k.modulus.equals(key.modulus)) isNew = false;
            if (isNew && save(key)) keys.add(key);
            allowed = keys.size();
        }
        writeStatus();
        return isNew;
    }

    /**
     * adbd checks keys and the client offered one it doesn't have (new, or saved but cleared
     * from adb_keys when the tile went off): add it, restart adbd so it is read, and let the
     * caller drop the connection. The computer's next connection signs with it.
     */
    private synchronized void offered(String who, AdbAuth.Key key) {
        boolean isNew = remember(key);
        if (!helperAdd(key.line)) {
            log(who + ": " + describe(key) + " could not be added to adb_keys");
            return;
        }
        log(who + ": " + describe(key) + (isNew ? " new key, saved" : " saved key") + " and added to adb_keys;"
            + " restarting adbd, it can connect again now");
        try {
            restartAdbd(adbdPort);
        } catch (IOException e) {
            log("warning: adbd restart after key add failed: " + message(e));
        }
    }

    /** Appends a key line to keysFile. Called with keys held. */
    private boolean save(AdbAuth.Key key) {
        try {
            FileOutputStream fos = new FileOutputStream(keysFile, true);
            try { fos.write((key.line + "\n").getBytes("UTF-8")); } finally { fos.close(); }
            return true;
        } catch (IOException e) {
            log("could not save key " + key.comment() + ": " + message(e));
            return false;
        }
    }

    // ---- firewall (root) ----

    /**
     * Makes sure INPUT jumps to CHAIN and that CHAIN drops adbd's internal port from anything but
     * loopback, and the public port from anything but Wi-Fi. Idempotent; rerun periodically in
     * case netd rebuilds INPUT.
     */
    private void firewallOn() throws IOException {
        firewallAt = System.currentTimeMillis();
        String adbd = Integer.toString(adbdPort), pub = Integer.toString(publicPort);
        iptables("-N", CHAIN); // fails harmlessly if it exists
        if (iptables("-F", CHAIN) != 0
                || iptables("-A", CHAIN, "-i", "lo", "-j", "RETURN") != 0
                || iptables("-A", CHAIN, "-p", "tcp", "--dport", adbd, "-j", "DROP") != 0
                || iptables("-A", CHAIN, "-p", "tcp", "--dport", pub, "!", "-i", "wlan+", "-j", "DROP") != 0
                || (iptables("-C", "INPUT", "-j", CHAIN) != 0 && iptables("-I", "INPUT", "-j", CHAIN) != 0)) {
            throw new IOException("could not set up the firewall");
        }
    }

    private void firewallOff() {
        for (int i = 0; i < 8 && iptables("-D", "INPUT", "-j", CHAIN) == 0; i++) { }
        iptables("-F", CHAIN);
        iptables("-X", CHAIN);
    }

    private int iptables(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "/system/bin/iptables";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            return exec(cmd, null);
        } catch (IOException e) {
            return 1;
        }
    }

    // ---- adbd control (root) ----

    private void restartAdbd(int port) throws IOException {
        setprop("service.adb.tcp.port", Integer.toString(port));
        setprop("ctl.stop", "adbd");
        waitAdbd("stopped", 5000);
        setprop("ctl.start", "adbd");
        if (!waitAdbd("running", 10000)) throw new IOException("adbd did not start on port " + port);
    }

    private void stopAdbd() throws IOException {
        // The firmware's init.rc starts adbd on 5555 whenever this is set to 1, boot included.
        setprop("persist.service.adb.tcp.enable", "0");
        // Negative: adbd won't use TCP if something starts it, and won't fall back to
        // persist.adb.tcp.port.
        setprop("service.adb.tcp.port", "-1");
        setprop("ctl.stop", "adbd");
        if (!waitAdbd("stopped", 5000)) throw new IOException("adbd did not stop");
    }

    /** Off and idle: is adbd listening over TCP anyway (state "keys"), and on which port? */
    private void observeAdbd() {
        if (state.equals("error")) return;
        int port = 0;
        if ("running".equals(getprop("init.svc.adbd"))) {
            // adbd's own rule: the live port, or the persistent one when the live one is unset.
            String live = getprop("service.adb.tcp.port");
            port = number(live.isEmpty() ? getprop("persist.adb.tcp.port") : live);
        }
        keysPort = port > 0 ? port : 0;
        state = keysPort > 0 ? "keys" : "off";
    }

    private boolean waitAdbd(String want, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (adbdIs(want)) return true;
            sleep(200);
        }
        return adbdIs(want);
    }

    /** init.svc.adbd is unset until adbd has run once; that counts as stopped. */
    private boolean adbdIs(String want) {
        String now = getprop("init.svc.adbd");
        return want.equals(now) || (want.equals("stopped") && now.isEmpty());
    }

    private void setprop(String name, String value) throws IOException {
        if (exec(new String[] {"/system/bin/setprop", name, value}, null) != 0) {
            throw new IOException("setprop " + name + " failed");
        }
    }

    /** Through the framework's SystemProperties when it loads (the idle poll reads several props a
     *  second), else the getprop binary. */
    private String getprop(String name) {
        try {
            if (systemPropertiesGet == null) {
                systemPropertiesGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            }
            return ((String) systemPropertiesGet.invoke(null, name)).trim();
        } catch (Exception e) {
            // fall through to the binary
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            exec(new String[] {"/system/bin/getprop", name}, out);
            return out.toString("UTF-8").trim();
        } catch (IOException e) {
            return "";
        }
    }

    // ---- adb_keys helper (uid 1000) ----

    /**
     * Writes the key where uid 1000 can read it, then asks the helper to add it. Synchronized:
     * there is one staging file, and computers can connect at the same moment.
     */
    private synchronized boolean helperAdd(String line) {
        try {
            FileOutputStream fos = new FileOutputStream(pendingKey);
            try { fos.write((line + "\n").getBytes("UTF-8")); } finally { fos.close(); }
            pendingKey.setReadable(true, false);
            int code = helper("add " + pendingKey.getAbsolutePath());
            pendingKey.delete();
            return code == 0;
        } catch (IOException e) {
            log("could not stage key: " + message(e));
            return false;
        }
    }

    private int helper(String args) {
        try {
            return exec(nemesisRoot(args), null);
        } catch (IOException e) {
            log("helper " + args + " failed: " + message(e));
            return 1;
        }
    }

    private String[] nemesisRoot(String args) {
        return new String[] {nemesis, "root", "1000", "-c", "sh " + keysHelper + " " + args};
    }

    // ---- process + status ----

    private int exec(String[] cmd, ByteArrayOutputStream capture) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(capture != null).start();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) if (capture != null && capture.size() < 65536) capture.write(buf, 0, n);
        try { return p.waitFor(); } catch (InterruptedException e) { return 1; }
    }

    private void writeStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(state).append('\n');
        sb.append("port=").append(state.equals("keys") ? keysPort : publicPort).append('\n');
        sb.append("clients=").append(clients.size()).append('\n');
        sb.append("allowed=").append(allowed).append('\n');
        if (!detail.isEmpty()) sb.append("detail=").append(detail).append('\n');
        File tmp = new File(stateDir, "status.tmp");
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try { fos.write(sb.toString().getBytes("UTF-8")); } finally { fos.close(); }
            tmp.setReadable(true, false);
            if (!tmp.renameTo(statusFile)) { statusFile.delete(); tmp.renameTo(statusFile); }
        } catch (IOException e) {
            log("status write failed: " + message(e));
        }
    }

    private void fail(String why) {
        state = "error";
        detail = why;
        errorAt = System.currentTimeMillis();
        log(why);
    }

    private static int number(String value) {
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static void close(Socket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static void log(String msg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        System.out.println(ts + " adbgate " + msg);
        System.out.flush();
    }
}
