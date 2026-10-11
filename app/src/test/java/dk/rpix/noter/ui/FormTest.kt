package dk.rpix.noter.ui

import dk.rpix.noter.core.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormTest {
    @Test fun tagsAreSplitTrimmedAndDeduplicated() {
        assertEquals(listOf("Privat", "Hjem"), parseTags(" Privat, Hjem ,, Privat,"))
        assertEquals(emptyList<String>(), parseTags(" , "))
    }

    @Test fun blankFormIsNotCreated() {
        assertTrue(Form("  ", "\n", emptyList()).isBlank())
        assertFalse(Form("", "", listOf("x")).isBlank())
    }

    @Test fun sameAsNormalizesNewlines() {
        val n = Note("u", null, null, "", "T", "a\nb", listOf("x"), "", false, false, false)
        assertTrue(Form("T", "a\r\nb", listOf("x")).sameAs(n))
        assertFalse(Form("T", "a\nb", listOf("y")).sameAs(n))
    }
}
