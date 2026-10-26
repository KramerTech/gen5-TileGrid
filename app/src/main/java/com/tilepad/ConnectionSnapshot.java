package com.tilepad;

import java.util.Locale;

/** Read-only display model; a Wi-Fi association does not imply an ADB handshake. */
final class ConnectionSnapshot {
    enum State { CONNECTED, DISCONNECTED, OFF, UNKNOWN }
    final State state;
    final String network;
    final String ip;
    final String adb;
    final boolean canCopy;
    /** Signal bars 0–3, or -1 when unknown or not connected. */
    final int signal;
    /** Link speed in Mbps, or 0 when unknown. */
    final int linkMbps;

    private ConnectionSnapshot(State state, String network, String ip, String adb, boolean canCopy) {
        this(state, network, ip, adb, canCopy, -1, 0);
    }
    private ConnectionSnapshot(State state, String network, String ip, String adb, boolean canCopy,
                               int signal, int linkMbps) {
        this.state = state;
        this.network = network;
        this.ip = ip;
        this.adb = adb;
        this.canCopy = canCopy;
        this.signal = signal;
        this.linkMbps = linkMbps;
    }

    /** Adds link quality to a connected snapshot; other states have none. */
    ConnectionSnapshot withLink(int signal, int linkMbps) {
        if (state != State.CONNECTED) return this;
        return new ConnectionSnapshot(state, network, ip, adb, canCopy,
            Math.max(-1, Math.min(3, signal)), Math.max(0, linkMbps));
    }

    /** The advertised ADB port, e.g. ":5555", or "" when ADB over TCP is not advertised. */
    String adbPort() {
        int colon = adb.lastIndexOf(':');
        return canCopy && colon > 0 ? adb.substring(colon) : "";
    }

    static ConnectionSnapshot unknown() {
        return new ConnectionSnapshot(State.UNKNOWN, "Unavailable", "—", "Unavailable", false);
    }

    static ConnectionSnapshot read(boolean enabled, boolean associated, String ssid, int address,
                                   String livePort, String persistentPort) {
        if (!enabled) return new ConnectionSnapshot(State.OFF, "Wi-Fi is off", "—", "—", false);
        if (!associated || address == 0) {
            return new ConnectionSnapshot(State.DISCONNECTED, "Not connected", "—", "—", false);
        }
        String network = ssid == null ? "" : ssid.trim();
        if (network.length() >= 2 && network.startsWith("\"") && network.endsWith("\"")) {
            network = network.substring(1, network.length() - 1);
        }
        if (network.isEmpty() || network.startsWith("<")) network = "Unknown network";
        String ip = formatIp(address);
        // A negative live port explicitly disables TCP and must not use a stale persistent port.
        String advertised = livePort == null || livePort.trim().isEmpty() ? persistentPort : livePort;
        int port = port(advertised);
        return new ConnectionSnapshot(State.CONNECTED, network, ip,
            port > 0 ? ip + ":" + port : "Not advertised", port > 0);
    }

    static String formatIp(int address) {
        return String.format(Locale.US, "%d.%d.%d.%d", address & 255,
            address >>> 8 & 255, address >>> 16 & 255, address >>> 24 & 255);
    }

    private static int port(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.trim());
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) { return -1; }
    }
}
