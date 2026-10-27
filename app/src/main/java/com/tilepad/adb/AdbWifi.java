package com.tilepad.adb;

/**
 * Keeps the Wi-Fi client up while the ADB tile is on: ADB over Wi-Fi needs the head unit on the
 * same network as the computer. The radio does either the client or the hotspot that wireless
 * CarPlay/Android Auto uses, never both, and the head unit boots with Wi-Fi off and the hotspot
 * up. So while ADB is on, the daemon turns the hotspot off and the client on, at boot and
 * whenever either changes, and wireless CarPlay doesn't work until ADB is turned off.
 *
 * Talks to the framework's IWifiManager through reflection (the daemon runs under app_process,
 * with no Context). Signatures checked against this firmware's services.odex: on its
 * WifiController, ApEnabledState ignores CMD_WIFI_TOGGLED, so the hotspot has to go first.
 * setWifiEnabled is the call Android's Wi-Fi checkbox makes; it also saves wifi_on.
 */
final class AdbWifi {
    // android.net.wifi.WifiManager constants
    private static final int WIFI_STATE_ENABLING = 2;
    private static final int WIFI_STATE_ENABLED = 3;
    private static final int WIFI_AP_STATE_DISABLING = 10;
    private static final int WIFI_AP_STATE_ENABLING = 12;
    private static final int WIFI_AP_STATE_ENABLED = 13;

    /** How long to give one step (hotspot off, client on) before trying again. */
    private static final long SETTLE_MS = 15000;

    interface Log { void log(String msg); }

    private final Log log;
    private Object service;
    private long actedAt;

    AdbWifi(Log log) { this.log = log; }

    /**
     * One step toward "client on". Returns true if this call turned something on or off (the
     * caller records that the client is ADB's to turn off later).
     */
    boolean keepClientOn() {
        if (System.currentTimeMillis() - actedAt < SETTLE_MS) return false;
        try {
            Object wifi = service();
            if (wifi == null) return false; // framework not up yet
            int client = (Integer) call(wifi, "getWifiEnabledState");
            if (client == WIFI_STATE_ENABLED || client == WIFI_STATE_ENABLING) return false;
            int ap = (Integer) call(wifi, "getWifiApEnabledState");
            if (ap == WIFI_AP_STATE_DISABLING) return false;
            actedAt = System.currentTimeMillis();
            if (ap == WIFI_AP_STATE_ENABLED || ap == WIFI_AP_STATE_ENABLING) {
                log.log("wifi: turning off the hotspot so the Wi-Fi client can run");
                iface().getMethod("setWifiApEnabled",
                    Class.forName("android.net.wifi.WifiConfiguration"), boolean.class).invoke(wifi, null, false);
            } else {
                log.log("wifi: turning on the Wi-Fi client");
                iface().getMethod("setWifiEnabled", boolean.class).invoke(wifi, true);
            }
            return true;
        } catch (Exception e) {
            service = null;
            log.log("wifi: " + e);
            return false;
        }
    }

    /** Turns the client off (ADB is off and the client was ADB's doing). */
    void clientOff() {
        try {
            Object wifi = service();
            if (wifi == null) return;
            log.log("wifi: turning off the Wi-Fi client");
            iface().getMethod("setWifiEnabled", boolean.class).invoke(wifi, false);
        } catch (Exception e) {
            service = null;
            log.log("wifi: " + e);
        }
    }

    private Object service() throws Exception {
        if (service == null) {
            Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "wifi");
            if (binder == null) return null;
            service = Class.forName("android.net.wifi.IWifiManager$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        }
        return service;
    }

    /** Methods come from the public interface: the binder proxy class itself is private. */
    private static Class<?> iface() throws ClassNotFoundException {
        return Class.forName("android.net.wifi.IWifiManager");
    }

    private static Object call(Object target, String method) throws Exception {
        return iface().getMethod(method).invoke(target);
    }
}
