package com.tilepad;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AdbSnapshotTest {
    @Test public void parsesTheDaemonStatusFile() {
        AdbSnapshot s = AdbSnapshot.parse("state=on\nport=5555\nclients=2\nallowed=3\ndetail=ignored\n");
        assertEquals(AdbSnapshot.State.ON, s.state);
        assertEquals(5555, s.port);
        assertEquals(2, s.clients);
        assertEquals(3, s.allowed);
    }

    @Test public void treatsMissingOrUnknownStateAsOff() {
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.parse(null).state);
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.parse("").state);
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.parse("state=wat\nport=x\n").state);
        assertEquals(0, AdbSnapshot.parse("state=wat\nport=x\n").port); // non-numeric ignored
    }

    @Test public void mapsEachState() {
        assertEquals(AdbSnapshot.State.STARTING, AdbSnapshot.parse("state=starting").state);
        assertEquals(AdbSnapshot.State.STOPPING, AdbSnapshot.parse("state=stopping").state);
        assertEquals(AdbSnapshot.State.ERROR, AdbSnapshot.parse("state=error").state);
        assertEquals(AdbSnapshot.State.KEYS, AdbSnapshot.parse("state=keys").state);
    }
}
