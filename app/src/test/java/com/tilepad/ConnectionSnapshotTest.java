package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionSnapshotTest {
    @Test public void connectedAddressUsesWifiByteOrderAndLiveAdbPort() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, true, "\"Network\"", 0x2a0200c0, "5555", "5556");
        assertEquals(ConnectionSnapshot.State.CONNECTED, value.state);
        assertEquals("Network", value.network);
        assertEquals("192.0.2.42", value.ip);
        assertEquals("192.0.2.42:5555", value.adb);
        assertTrue(value.canCopy);
    }
    @Test public void disconnectedAssociationDoesNotShowStaleIp() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, false, "Previous", 0x2a0200c0, "5555", "");
        assertEquals(ConnectionSnapshot.State.DISCONNECTED, value.state);
        assertEquals("—", value.ip);
        assertFalse(value.canCopy);
    }
    @Test public void wifiOffAndNoDhcpAddressHaveNoCopyableEndpoint() {
        assertEquals(ConnectionSnapshot.State.OFF, ConnectionSnapshot.read(false, true, "N", 12, "5555", "").state);
        assertFalse(ConnectionSnapshot.read(true, true, "N", 0, "5555", "").canCopy);
    }
    @Test public void disabledLivePortDoesNotFallBackToStalePersistentPort() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, true, "N", 1, "-1", "5555");
        assertEquals("Not advertised", value.adb);
        assertFalse(value.canCopy);
    }
    @Test public void absentLivePortCanUsePersistentPortAndRejectsMalformedPorts() {
        assertEquals("1.0.0.0:5555", ConnectionSnapshot.read(true, true, "N", 1, "", "5555").adb);
        for (String bad : new String[]{"", "0", "65536", "bad"}) {
            assertFalse(ConnectionSnapshot.read(true, true, "N", 1, bad, "").canCopy);
        }
    }
    @Test public void unknownSsidStillDisplaysValidIpAndUnknownStateIsNotConnected() {
        assertEquals("Unknown network", ConnectionSnapshot.read(true, true, "<unknown ssid>", 1, "5555", "").network);
        assertEquals(ConnectionSnapshot.State.UNKNOWN, ConnectionSnapshot.unknown().state);
        assertFalse(ConnectionSnapshot.unknown().canCopy);
    }
    @Test public void linkQualityOnlyAppliesWhenConnectedAndIsClamped() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, true, "N", 1, "5555", "").withLink(7, 72);
        assertEquals(3, value.signal);
        assertEquals(72, value.linkMbps);
        assertEquals(":5555", value.adbPort());
        ConnectionSnapshot off = ConnectionSnapshot.read(false, false, null, 0, "", "").withLink(2, 72);
        assertEquals(-1, off.signal);
        assertEquals("", off.adbPort());
        assertEquals("", ConnectionSnapshot.read(true, true, "N", 1, "", "").adbPort());
    }
}
