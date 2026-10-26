package com.tilepad;

/**
 * Connection state, network name and address; tapping opens Android's network picker.
 * On a hotspot-only unit it offers to turn on the Wi-Fi client instead.
 */
final class WifiTile extends StatusTile {
    WifiTile(Ui ui, TileSpec spec, EditListener edits) {
        super(ui, spec, edits, Glyph.Kind.WIFI, null);
        bind(ConnectionSnapshot.unknown());
    }

    void bind(ConnectionSnapshot c) {
        switch (c.state) {
            case CONNECTED: {
                glyph.setKind(Glyph.Kind.WIFI);
                glyph.setColor(Ui.ACCENT);
                glyph.setSignal(c.signal < 0 ? 3 : c.signal);
                setPill("Connected", Ui.GREEN);
                show(headline, c.network);
                show(detail, c.ip);
                String speed = c.linkMbps > 0 ? c.linkMbps + " Mbps" : "";
                String adb = c.adbPort().isEmpty() ? "" : "ADB " + c.adbPort();
                show(meta, speed.isEmpty() || adb.isEmpty() ? speed + adb : speed + " · " + adb);
                setContentDescription("Wi-Fi connected to " + c.network + ", " + c.ip);
                break;
            }
            case DISCONNECTED:
                glyph.setKind(Glyph.Kind.WIFI);
                glyph.setColor(Ui.MUTED); glyph.setSignal(0);
                setPill("No network", Ui.AMBER);
                show(headline, "Not connected");
                show(detail, "Tap to choose");
                show(meta, "");
                setContentDescription("Wi-Fi not connected");
                break;
            case OFF:
                glyph.setKind(Glyph.Kind.WIFI_OFF);
                glyph.setColor(Ui.MUTED); glyph.setSignal(0);
                setPill("Off", Ui.MUTED);
                show(headline, "Wi-Fi off");
                show(detail, "Tap to open settings");
                show(meta, "");
                setContentDescription("Wi-Fi off");
                break;
            default:
                glyph.setKind(Glyph.Kind.WIFI);
                glyph.setColor(Ui.MUTED); glyph.setSignal(0);
                setPill(null, Ui.MUTED);
                show(headline, "Wi-Fi");
                show(detail, "Checking…");
                show(meta, "");
                setContentDescription("Wi-Fi");
        }
    }

    /** The unit can't join networks yet; pendingReboot once the client is on but not started. */
    void bindClientOff(boolean pendingReboot) {
        glyph.setKind(Glyph.Kind.WIFI_OFF);
        glyph.setColor(Ui.MUTED); glyph.setSignal(0);
        setPill(pendingReboot ? "Reboot" : "Client off", pendingReboot ? Ui.AMBER : Ui.MUTED);
        show(headline, pendingReboot ? "Wi-Fi client on" : "Hotspot only");
        show(detail, pendingReboot ? "Reboot to finish" : "Tap to turn on Wi-Fi");
        show(meta, "");
        setContentDescription(pendingReboot ? "Wi-Fi client on, reboot to finish" : "Wi-Fi client off");
    }
}
