package dk.rpix.noter.ui

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DateLabelsTest {
    private val cph = TimeZone.getTimeZone("Europe/Copenhagen")
    private val da = Locale.forLanguageTag("da-DK")
    private val en = Locale.forLanguageTag("en-US")
    private val androidPattern = DateLabels.bestPattern

    /** De mønstre, Android (ICU) giver for skeletterne på dansk og amerikansk engelsk. */
    @Before fun fakePatterns() {
        val patterns = mapOf(
            "da" to mapOf("jm" to "HH.mm", "yMMMM" to "MMMM y", "MMMd" to "d. MMM", "EEE" to "EEE"),
            "en" to mapOf("jm" to "h:mm a", "yMMMM" to "MMMM y", "MMMd" to "MMM d", "EEE" to "EEE"),
        )
        DateLabels.bestPattern = { locale, skeleton -> patterns.getValue(locale.language).getValue(skeleton) }
    }

    @After fun restorePatterns() {
        DateLabels.bestPattern = androidPattern
    }

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

    @Test fun labelsFollowLanguage() {
        val now = local(2026, 10, 11, 18, 0)
        assertEquals("14.05", DateLabels.shortTime(local(2026, 10, 11, 14, 5), now, cph, da))
        assertEquals("oktober 2026", DateLabels.monthLabel(DateGroup.Month(2026, Calendar.OCTOBER), cph, da))
        assertEquals("3. sep.", DateLabels.shortTime(local(2026, 9, 3), now, cph, da))

        assertEquals("2:05 PM", DateLabels.shortTime(local(2026, 10, 11, 14, 5), now, cph, en))
        assertEquals("October 2026", DateLabels.monthLabel(DateGroup.Month(2026, Calendar.OCTOBER), cph, en))
        assertEquals("Sep 3", DateLabels.shortTime(local(2026, 9, 3), now, cph, en))
    }
}
