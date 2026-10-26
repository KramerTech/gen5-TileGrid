package com.tilepad;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class PreconditioningProfileTest {
    static final class Recorder implements PreconditioningProfile.Commands {
        final List<String> events = new ArrayList<>();
        int failedTransaction = -1;
        @Override public void write(int transaction, int... values) throws Exception {
            events.add(transaction + ":" + Arrays.toString(values));
            assertTrue("No global preference or other vehicle command", transaction == 62 || transaction == 65 || transaction == 66);
            if (transaction == failedTransaction) throw new Exception("Transport failure");
        }
        @Override public void pause(long milliseconds) { events.add("pause:" + milliseconds); }
    }
    @Test public void startUsesDecodedMetadataBeforeTheSingleOemPulse() throws Exception {
        Recorder commands = new Recorder(); PreconditioningProfile.start(commands);
        assertEquals(Arrays.asList("65:[9]", "66:[18]", "pause:10", "62:[2, 3]", "pause:130"), commands.events);
        // The linked start log's 4ED bytes 09 90 00 match the stock 11+10-bit fields.
        int loggedPacked = 0x009009;
        assertEquals(9, loggedPacked & 0x7ff); assertEquals(18, (loggedPacked >>> 11) & 0x3ff);
    }
    @Test public void refreshCannotSendAnotherStationPulse() throws Exception {
        Recorder commands = new Recorder();
        for (int i = 0; i < 30; i++) PreconditioningProfile.maintain(commands);
        for (String event : commands.events) assertFalse(event.startsWith("62:"));
        assertEquals(5000, PreconditioningProfile.REFRESH_MS);
    }
    @Test public void cancelAttemptsBothInvalidationsAndOffEvenAfterACommunicationFailure() {
        for (int failed : new int[]{65, 66, 62}) {
            Recorder commands = new Recorder(); commands.failedTransaction = failed;
            try { PreconditioningProfile.cancel(commands); fail("Must report incomplete cancellation"); }
            catch (Exception expected) { assertEquals("Transport failure", expected.getMessage()); }
            assertEquals(Arrays.asList("65:[2047]", "66:[1023]", "62:[0, 0]", "pause:130"), commands.events);
        }
    }
    @Test public void uncertainStartPulseStillFinishesItsResetWindowBeforeCancellation() {
        Recorder commands = new Recorder(); commands.failedTransaction = 62;
        try { PreconditioningProfile.start(commands); fail("Must report uncertain Start"); }
        catch (Exception expected) { assertEquals("Transport failure", expected.getMessage()); }
        assertEquals("pause:130", commands.events.get(commands.events.size() - 1));
    }
}
