package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionSnapshotTest {
    @Test public void connectedAddressUsesWifiByteOrder() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, true, "\"Network\"", 0x2a0200c0);
        assertEquals(ConnectionSnapshot.State.CONNECTED, value.state);
        assertEquals("Network", value.network);
        assertEquals("192.0.2.42", value.ip);
    }
    @Test public void disconnectedAssociationDoesNotShowStaleIp() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, false, "Previous", 0x2a0200c0);
        assertEquals(ConnectionSnapshot.State.DISCONNECTED, value.state);
        assertEquals("—", value.ip);
    }
    @Test public void wifiOffAndNoDhcpAddressAreNotConnected() {
        assertEquals(ConnectionSnapshot.State.OFF, ConnectionSnapshot.read(false, true, "N", 12).state);
        assertEquals(ConnectionSnapshot.State.DISCONNECTED, ConnectionSnapshot.read(true, true, "N", 0).state);
    }
    @Test public void unknownSsidStillDisplaysValidIpAndUnknownStateIsNotConnected() {
        assertEquals("Unknown network", ConnectionSnapshot.read(true, true, "<unknown ssid>", 1).network);
        assertEquals(ConnectionSnapshot.State.UNKNOWN, ConnectionSnapshot.unknown().state);
    }
    @Test public void linkQualityOnlyAppliesWhenConnectedAndIsClamped() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, true, "N", 1).withLink(7, 72);
        assertEquals(3, value.signal);
        assertEquals(72, value.linkMbps);
        ConnectionSnapshot off = ConnectionSnapshot.read(false, false, null, 0).withLink(2, 72);
        assertEquals(-1, off.signal);
    }
}
