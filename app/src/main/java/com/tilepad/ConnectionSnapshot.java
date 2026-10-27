package com.tilepad;

import java.util.Locale;

/** Read-only display model for the Wi-Fi tile. */
final class ConnectionSnapshot {
    enum State { CONNECTED, DISCONNECTED, OFF, UNKNOWN }
    final State state;
    final String network;
    final String ip;
    /** Signal bars 0–3, or -1 when unknown or not connected. */
    final int signal;
    /** Link speed in Mbps, or 0 when unknown. */
    final int linkMbps;

    private ConnectionSnapshot(State state, String network, String ip) {
        this(state, network, ip, -1, 0);
    }
    private ConnectionSnapshot(State state, String network, String ip, int signal, int linkMbps) {
        this.state = state;
        this.network = network;
        this.ip = ip;
        this.signal = signal;
        this.linkMbps = linkMbps;
    }

    /** Adds link quality to a connected snapshot; other states have none. */
    ConnectionSnapshot withLink(int signal, int linkMbps) {
        if (state != State.CONNECTED) return this;
        return new ConnectionSnapshot(state, network, ip,
            Math.max(-1, Math.min(3, signal)), Math.max(0, linkMbps));
    }

    static ConnectionSnapshot unknown() {
        return new ConnectionSnapshot(State.UNKNOWN, "Unavailable", "—");
    }

    static ConnectionSnapshot read(boolean enabled, boolean associated, String ssid, int address) {
        if (!enabled) return new ConnectionSnapshot(State.OFF, "Wi-Fi is off", "—");
        if (!associated || address == 0) {
            return new ConnectionSnapshot(State.DISCONNECTED, "Not connected", "—");
        }
        String network = ssid == null ? "" : ssid.trim();
        if (network.length() >= 2 && network.startsWith("\"") && network.endsWith("\"")) {
            network = network.substring(1, network.length() - 1);
        }
        if (network.isEmpty() || network.startsWith("<")) network = "Unknown network";
        return new ConnectionSnapshot(State.CONNECTED, network, formatIp(address));
    }

    static String formatIp(int address) {
        return String.format(Locale.US, "%d.%d.%d.%d", address & 255,
            address >>> 8 & 255, address >>> 16 & 255, address >>> 24 & 255);
    }
}
