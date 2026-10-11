package dk.rpix.noter.ui

import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateLabelsTest {
    private val cph = TimeZone.getTimeZone("Europe/Copenhagen")

    private fun local(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Date =
        Calendar.getInstance(cph).apply { clear(); set(y, m - 1, d, h, min) }.time

    @Test fun parsesUtcAndLocalStamps() {
        assertEquals(local(2026, 10, 10, 19, 0), DateLabels.parseStamp("20261010T170000Z", cph))
        assertEquals(local(2026, 10, 10, 17, 0), DateLabels.parseStamp("20261010T170000", cph))
        assertNull(DateLabels.parseStamp("", cph))
        assertNull(DateLabels.parseStamp("2026-10-10", cph))
    }

    @Test fun groups() {
        val now = local(2026, 10, 11, 9, 0)
        assertEquals(DateGroup.Today, DateLabels.groupOf(local(2026, 10, 11, 0, 5), now, cph))
        assertEquals(DateGroup.Yesterday, DateLabels.groupOf(local(2026, 10, 10, 23, 59), now, cph))
        assertEquals(DateGroup.Last7Days, DateLabels.groupOf(local(2026, 10, 5), now, cph))
        assertEquals(DateGroup.Month(2026, Calendar.OCTOBER), DateLabels.groupOf(local(2026, 10, 4), now, cph))
        assertEquals(DateGroup.Month(2025, Calendar.DECEMBER), DateLabels.groupOf(local(2025, 12, 24), now, cph))
        assertEquals(DateGroup.NoDate, DateLabels.groupOf(null, now, cph))
        assertEquals(DateGroup.Today, DateLabels.groupOf(local(2026, 10, 12), now, cph)) // fremtid = i dag
    }

    @Test fun dayDiffAcrossDaylightSavingChange() {
        // Sommertid slutter 25. oktober 2026
        assertEquals(1, DateLabels.dayDiff(local(2026, 10, 25, 1), local(2026, 10, 26, 0, 30), cph))
        assertEquals(1, DateLabels.dayDiff(local(2026, 3, 28, 23), local(2026, 3, 29, 23), cph))
    }

    @Test fun labelsAreDanish() {
        val now = local(2026, 10, 11, 18, 0)
        assertEquals("14.05", DateLabels.shortTime(local(2026, 10, 11, 14, 5), now, cph))
        assertEquals("oktober 2026", DateLabels.monthLabel(DateGroup.Month(2026, Calendar.OCTOBER), cph))
        assertEquals("3. sep.", DateLabels.shortTime(local(2026, 9, 3), now, cph))
    }
}
