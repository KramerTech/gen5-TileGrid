package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreconPresenterTest {
    private static PreconditioningSnapshot snap(PreconditioningSnapshot.State state, boolean requested,
                                                boolean canStart, boolean canStop, int indicator) {
        return new PreconditioningSnapshot(state, requested, canStart, canStop, indicator, -1, 65000, "detail");
    }

    @Test public void tapStartsWhenStartableAndStopsOnlyWhenActive() {
        assertEquals(PreconPresenter.Tap.START, PreconPresenter.tap(snap(PreconditioningSnapshot.State.IDLE, false, true, false, 0)));
        assertEquals(PreconPresenter.Tap.STOP, PreconPresenter.tap(snap(PreconditioningSnapshot.State.ACTIVE, true, false, true, 1)));
        // Waiting: a tap shows the popup (with Cancel) instead of cancelling.
        assertEquals(PreconPresenter.Tap.STATUS, PreconPresenter.tap(snap(PreconditioningSnapshot.State.WAITING, true, false, true, 0)));
        assertEquals(PreconPresenter.Tap.STATUS, PreconPresenter.tap(snap(PreconditioningSnapshot.State.UNAVAILABLE, false, false, false, 0)));
        assertEquals(PreconPresenter.Tap.STATUS, PreconPresenter.tap(snap(PreconditioningSnapshot.State.ERROR, true, false, true, 0)));
    }

    @Test public void onlyTheCarsIndicatorReadsAsActive() {
        PreconditioningSnapshot waiting = snap(PreconditioningSnapshot.State.WAITING, true, false, true, 0);
        assertEquals("Waiting", PreconPresenter.headline(waiting));
        assertTrue(PreconPresenter.busy(waiting));
        assertEquals("Elapsed 1:05", PreconPresenter.footer(waiting));
        assertEquals("Active", PreconPresenter.headline(snap(PreconditioningSnapshot.State.ACTIVE, true, false, true, 1)));
        assertEquals(PreconPresenter.Tone.GOOD, PreconPresenter.tone(snap(PreconditioningSnapshot.State.ACTIVE, true, false, true, 1)));
        assertEquals("Car active", PreconPresenter.headline(snap(PreconditioningSnapshot.State.IDLE, false, false, false, 1)));
    }

    @Test public void idleTilesInviteStartOrExplain() {
        assertEquals("Tap to start", PreconPresenter.footer(snap(PreconditioningSnapshot.State.IDLE, false, true, false, 0)));
        assertEquals(PreconPresenter.Tone.READY, PreconPresenter.tone(snap(PreconditioningSnapshot.State.IDLE, false, true, false, 0)));
        assertEquals("Tap for details", PreconPresenter.footer(snap(PreconditioningSnapshot.State.UNAVAILABLE, false, false, false, 0)));
        assertEquals("Off", PreconPresenter.headline(snap(PreconditioningSnapshot.State.ENDED, false, true, false, 0)));
        assertNull(PreconPresenter.indicator(snap(PreconditioningSnapshot.State.CHECKING, false, false, false, -1)));
        assertNull(PreconPresenter.indicator(snap(PreconditioningSnapshot.State.IDLE, false, true, false, 0)));
        assertEquals("Conditioning", PreconPresenter.indicator(snap(PreconditioningSnapshot.State.ACTIVE, true, false, true, 1)));
    }
    @Test public void waitingDetailDropsTheRepeatedTitle() {
        assertEquals("Activation may take about 1–2 minutes.",
            PreconPresenter.withoutPrefix("Waiting for BMS — activation may take about 1–2 minutes.", "Waiting for BMS — "));
        assertEquals("Other text", PreconPresenter.withoutPrefix("Other text", "Waiting for BMS — "));
    }
}
