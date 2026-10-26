package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class SearchFilterTest {
    @Test public void everyWordMustMatchLabelOrComponentIgnoringCase() {
        assertTrue(SearchFilter.matches("", "Navigation", "com.mnsoft.navi/.NaviApp"));
        assertTrue(SearchFilter.matches("  NAV  ", "Navigation", "x/.Y"));
        assertTrue(SearchFilter.matches("mnsoft navi", "Navigation", "com.mnsoft.navi/.NaviApp"));
        assertTrue(SearchFilter.matches("engineering", "Settings", "com.hkmc.system.app.engineering/.Main"));
        assertFalse(SearchFilter.matches("nav phone", "Navigation", "com.mnsoft.navi/.NaviApp"));
        assertFalse(SearchFilter.matches("radio", null, null));
    }

    @Test public void labelHitsRankAbovePackageNameHits() {
        assertEquals(0, SearchFilter.rank("set", "Settings"));
        assertEquals(0, SearchFilter.rank("", "Anything"));
        assertEquals(1, SearchFilter.rank("wi set", "Wi-Fi settings"));
        assertEquals(2, SearchFilter.rank("set", "Accessibility"));
    }

}
