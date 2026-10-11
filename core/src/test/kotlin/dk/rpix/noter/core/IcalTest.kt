package dk.rpix.noter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** iCalendar-laget, som i første del af reference/selftest.py. */
class IcalTest {
    @Test fun roundTripOfSpecialCharacters() {
        val raw = Ical.newIcs("u-1", "Indkøb, og mere; ting", "Mælk\nBrød\\rugbrød", listOf("Privat", "A,B"))
        val p = Ical.parseIcs(raw)
        assertEquals("rundtur: titel med komma/semikolon", "Indkøb, og mere; ting", p.title)
        assertEquals("rundtur: flere linjer og backslash", "Mælk\nBrød\\rugbrød", p.body)
        assertEquals("rundtur: tags (inkl. komma i tag)", listOf("Privat", "A,B"), p.tags)
        assertEquals("u-1", p.uid)
    }

    @Test fun newIcsHasRequiredPropertiesAndCrlf() {
        val raw = Ical.newIcs("u-1", "T")
        assertTrue(raw.endsWith("\r\n"))
        assertTrue(raw.replace("\r\n", "").none { it == '\n' || it == '\r' })
        for (needle in listOf("PRODID:${Ical.PRODID}", "UID:u-1", "DTSTAMP:", "CREATED:", "LAST-MODIFIED:", "SUMMARY:T")) {
            assertTrue(needle, needle in raw)
        }
        assertTrue("ingen tom DESCRIPTION", "DESCRIPTION" !in raw)
        assertTrue("ingen tom CATEGORIES", "CATEGORIES" !in raw)
        assertTrue(Regex("""LAST-MODIFIED:\d{8}T\d{6}Z""").containsMatchIn(raw))
    }

    @Test fun foldingKeepsLinesWithin75OctetsAndRoundTrips() {
        val longBody = "Lang linje med æøå og 😀 ".repeat(40).trim()
        val raw = Ical.newIcs("u-2", "Lang", longBody)
        val phys = raw.split("\r\n").dropLast(1)
        val max = phys.maxOf { it.toByteArray(Charsets.UTF_8).size }
        assertTrue("folding: alle linjer <= 75 oktetter ($max)", max <= 75)
        assertTrue("fortsættelseslinjer begynder med mellemrum", phys.drop(1).any { it.startsWith(" ") })
        assertEquals("folding: rundtur uden tab af æøå/emoji", longBody, Ical.parseIcs(raw).body)
    }

    @Test fun foldNeverSplitsMultibyteCharacters() {
        for (n in 70..80) {
            val line = "X".repeat(n) + "😀æ".repeat(30)
            val folded = Ical.fold(line)
            for (part in folded.split("\r\n")) {
                assertTrue(part.toByteArray(Charsets.UTF_8).size <= 75)
                assertEquals("ingen halve surrogatpar", part, String(part.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            }
            assertEquals(line, Ical.unfold(folded).single())
        }
    }

    @Test fun unescapeAcceptsUpperN() {
        assertEquals("a\nb\nc;d,e\\", Ical.unescapeText("a\\nb\\Nc\\;d\\,e\\\\"))
        assertEquals("x", Ical.unescapeText("\\x"))
    }

    @Test fun propertyNamesAreCaseInsensitiveAndCategoriesAreMerged() {
        val raw = "BEGIN:VCALENDAR\r\nbegin:vjournal\r\nuid:abc\r\nsummary:Titel\r\n" +
            "Categories:A,B\\,C\r\nCATEGORIES:D\r\nend:vjournal\r\nEND:VCALENDAR\r\n"
        val p = Ical.parseIcs(raw)
        assertEquals("abc", p.uid)
        assertEquals("Titel", p.title)
        assertEquals(listOf("A", "B,C", "D"), p.tags)
        assertEquals("", p.modified)
    }

    @Test fun lfOnlyAndTabFoldingAreRead() {
        val raw = "BEGIN:VCALENDAR\nBEGIN:VJOURNAL\nUID:x\nSUMMARY:Hej\n\tverden\nEND:VJOURNAL\nEND:VCALENDAR\n"
        assertEquals("Hejverden", Ical.parseIcs(raw).title)
    }

    @Test(expected = IcalException::class) fun eventIsNotANote() {
        Ical.parseIcs(SyncScenarios.EVENT)
    }

    @Test(expected = IcalException::class) fun journalWithoutUidIsNotANote() {
        Ical.parseIcs("BEGIN:VCALENDAR\r\nBEGIN:VJOURNAL\r\nSUMMARY:x\r\nEND:VJOURNAL\r\nEND:VCALENDAR\r\n")
    }

    @Test fun updatePreservesForeignProperties() {
        val upd = Ical.updateIcs(FOREIGN, title = "Ny titel")
        for (needle in listOf(
            "CLASS:PUBLIC", "STATUS:FINAL", "X-JTX-FOO:bar", "CATEGORIES:Arbejde,Privat",
            "ATTACH;FMTTYPE=\"text/x:y\":https://example.dk/a.txt", "PRODID:-//ical4j//jtx Board//EN",
            "DESCRIPTION;LANGUAGE=da:Hej\\, verden", "SEQUENCE:3",
        )) {
            assertTrue("update bevarer: $needle", needle in upd)
        }
        assertEquals("update ændrer titlen", "Ny titel", Ical.parseIcs(upd).title)
        assertTrue("DTSTAMP er fornyet", "DTSTAMP:20260101T100000Z" !in upd)
        assertTrue("LAST-MODIFIED er sat", Ical.parseIcs(upd).modified.isNotEmpty())
        assertEquals("kun én SUMMARY", 1, Regex("(?m)^SUMMARY").findAll(upd).count())
    }

    @Test fun updateWithoutChangeReturnsSameText() {
        assertSame(FOREIGN, Ical.updateIcs(FOREIGN, title = "Fra jtx"))
        assertSame(FOREIGN, Ical.updateIcs(FOREIGN, title = "Fra jtx", body = "Hej, verden", tags = listOf("Arbejde", "Privat")))
        assertSame("CRLF i tekst tæller ikke som ændring", FOREIGN, Ical.updateIcs(FOREIGN, body = "Hej, verden".replace("\n", "\r\n")))
    }

    @Test fun updateOfBodyAndTags() {
        val upd = Ical.updateIcs(FOREIGN, body = "Linje 1\r\nLinje 2", tags = listOf("Ny"))
        val p = Ical.parseIcs(upd)
        assertEquals("Linje 1\nLinje 2", p.body)
        assertEquals(listOf("Ny"), p.tags)
        assertTrue("parametre på DESCRIPTION forsvinder kun, når teksten ændres", "LANGUAGE=da" !in upd)
        val cleared = Ical.updateIcs(upd, body = "", tags = emptyList())
        assertTrue("DESCRIPTION" !in cleared && "CATEGORIES" !in cleared)
        assertNotEquals(upd, cleared)
    }

    companion object {
        /** Note skrevet af en 'fremmed' klient (som jtx Board) med egenskaber, vi ikke kender. */
        const val FOREIGN =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//ical4j//jtx Board//EN\r\n" +
                "BEGIN:VJOURNAL\r\nUID:foreign-1\r\nDTSTAMP:20260101T100000Z\r\nSEQUENCE:3\r\n" +
                "SUMMARY:Fra jtx\r\nDESCRIPTION;LANGUAGE=da:Hej\\, verden\r\n" +
                "CATEGORIES:Arbejde,Privat\r\nCLASS:PUBLIC\r\nSTATUS:FINAL\r\nX-JTX-FOO:bar\r\n" +
                "ATTACH;FMTTYPE=\"text/x:y\":https://example.dk/a.txt\r\n" +
                "END:VJOURNAL\r\nEND:VCALENDAR\r\n"
    }
}
