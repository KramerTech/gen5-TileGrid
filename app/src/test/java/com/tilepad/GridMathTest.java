package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class GridMathTest {
    // Car: 1280x420dp window minus padding (24 left, 64 right, 16 top/bottom), 16dp gap.
    @Test public void carScreenFitsTwoRowsOfSixSquares() {
        GridMath m = GridMath.compute(1192, 388, 16, 150, 240);
        assertEquals(2, m.rows);
        assertEquals(6, m.cols);
        assertTrue(m.tile >= 180 && m.tile <= 186);
        assertTrue(m.cols * m.tile + (m.cols - 1) * m.gap <= 1192);
    }

    @Test public void slightlyNarrowWindowStillGetsSixColumns() {
        assertEquals(6, GridMath.compute(1180, 388, 16, 150, 240).cols);
    }

    @Test public void slotsAndHitTestingAgree() {
        GridMath m = GridMath.compute(1192, 388, 16, 150, 240);
        for (int i = 0; i < 14; i++) {
            float cx = m.left(i) + m.tile / 2f, cy = m.top(i) + m.tile / 2f;
            assertEquals(i, m.indexAt(cx, cy, 14));
        }
        assertEquals(13, m.indexAt(99999, 99999, 14));
        assertEquals(0, m.indexAt(-500, -500, 14));
        assertEquals(-1, m.indexAt(0, 0, 0));
    }

    @Test public void contentHeightGrowsByRows() {
        GridMath m = GridMath.compute(1192, 388, 16, 150, 240);
        assertEquals(m.tile, m.contentHeight(1));
        assertEquals(2 * m.tile + 16, m.contentHeight(12));
        assertEquals(3 * m.tile + 32, m.contentHeight(13));
    }

    @Test public void tinyViewportsNeverProduceZeroSizes() {
        GridMath m = GridMath.compute(0, 0, 16, 150, 240);
        assertTrue(m.rows >= 1 && m.cols >= 1 && m.tile >= 1);
    }
}
