package com.tilepad;

/**
 * Read-only display model for the ADB tile. The real state lives in the relay daemon
 * ({@link com.tilepad.adb.AdbGate}), which writes a small key=value status file that the
 * tile reads over the privileged channel. "On" means the daemon is fronting the ADB port
 * and letting every computer in; "off" means nothing listens for ADB over TCP; "keys" means
 * adbd is listening anyway (something else turned it on), for already-approved computers only.
 */
final class AdbSnapshot {
    enum State { CHECKING, OFF, KEYS, STARTING, ON, STOPPING, ERROR }

    final State state;
    /** The port computers connect to, e.g. 5555 (adbd's own port in state KEYS). */
    final int port;
    /** Computers connected through the relay right now. */
    final int clients;
    /** Computers the relay has let in (keys it added), 0 when off. */
    final int allowed;
    final String detail;

    private AdbSnapshot(State state, int port, int clients, int allowed, String detail) {
        this.state = state;
        this.port = port;
        this.clients = clients;
        this.allowed = allowed;
        this.detail = detail == null ? "" : detail;
    }

    static AdbSnapshot checking() { return new AdbSnapshot(State.CHECKING, 0, 0, 0, ""); }

    static AdbSnapshot error(String detail) { return new AdbSnapshot(State.ERROR, 0, 0, 0, detail); }

    static AdbSnapshot of(State state, int port, int clients, int allowed, String detail) {
        return new AdbSnapshot(state, port, clients, allowed, detail);
    }

    /**
     * Parses the daemon's status file. An empty or missing file means the daemon hasn't
     * written one yet (never enabled) — treated as off. A line starting with "nemesis" or a
     * non-zero exit is surfaced by the caller as an error, not here.
     */
    static AdbSnapshot parse(String text) {
        String state = "off", detail = "";
        int port = 0, clients = 0, allowed = 0;
        if (text != null) {
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (key.equals("state")) state = value;
                else if (key.equals("port")) port = num(value);
                else if (key.equals("clients")) clients = num(value);
                else if (key.equals("allowed")) allowed = num(value);
                else if (key.equals("detail")) detail = value;
            }
        }
        return new AdbSnapshot(state(state), port, clients, allowed, detail);
    }

    private static State state(String value) {
        if (value.equals("on")) return State.ON;
        if (value.equals("keys")) return State.KEYS;
        if (value.equals("starting")) return State.STARTING;
        if (value.equals("stopping")) return State.STOPPING;
        if (value.equals("error")) return State.ERROR;
        return State.OFF;
    }

    private static int num(String value) {
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
