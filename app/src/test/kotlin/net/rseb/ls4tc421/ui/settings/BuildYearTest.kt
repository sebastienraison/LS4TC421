package net.rseb.ls4tc421.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The copyright year is the year of BUILD_DATE (« yyyy-MM-dd »); any other form shows no year (after Bivouac). */
class BuildYearTest {
    @Test fun theYearIsTakenFromTheBuildDate() {
        assertEquals("2026", buildYearOrNull("2026-11-16"))
        assertEquals("2026", buildYearOrNull("2026-01-01"))
        assertEquals("2025", buildYearOrNull("2025-12-31"))
    }

    @Test fun anUnexpectedFormGivesNoYear() {
        assertNull(buildYearOrNull(""))
        assertNull(buildYearOrNull("unknown"))
        assertNull(buildYearOrNull("16/11/2026"))
        assertNull(buildYearOrNull("26-11-16"))
        assertNull(buildYearOrNull("2026-1-16"))
        assertNull(buildYearOrNull("20261116"))
        assertNull(buildYearOrNull(" 2026-11-16"))
    }
}
