package dk.rpix.noter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lokale handlinger (afsnit 3 og 5 i docs/SPEC.md). */
class MemoryNoteStoreTest {
    private val s = MemoryNoteStore()

    @Test fun addIsDirtyWithoutHref() {
        val n = s.add("Titel", "Tekst", listOf("t"))
        assertTrue(n.dirty)
        assertNull(n.href)
        assertEquals(listOf(n), s.pending())
    }

    @Test fun editWithoutChangeDoesNothing() {
        val n = s.add("Titel", "Tekst")
        s.markPushed(n.uid, "/a/${n.uid}.ics", "\"1\"")
        val clean = s.byUid(n.uid)!!
        assertSame(clean, s.edit(n.uid, title = "Titel", body = "Tekst"))
        assertTrue(s.pending().isEmpty())
        val edited = s.edit(n.uid, body = "Ny")!!
        assertTrue(edited.dirty)
        assertEquals("Ny", edited.body)
    }

    @Test fun deleteNoteRemovesUnsentButMarksSent() {
        val unsent = s.add("a")
        s.deleteNote(unsent.uid)
        assertNull(s.byUid(unsent.uid))

        val sent = s.add("b")
        s.markPushed(sent.uid, "/a/b.ics", "\"1\"")
        s.deleteNote(sent.uid)
        val n = s.byUid(sent.uid)!!
        assertTrue(n.deleted && n.dirty)
        assertTrue(s.visible().isEmpty())
    }

    @Test fun visibleIsSortedByModifiedThenTitle() {
        s.insert("1", null, null, "", ParsedNote("1", "B", "", modified = "20260101T000000Z"))
        s.insert("2", null, null, "", ParsedNote("2", "A", "", modified = "20260101T000000Z"))
        s.insert("3", null, null, "", ParsedNote("3", "C", "", modified = "20261010T000000Z"))
        s.insert("4", "/x", "\"e\"", "", null, ignored = true)
        assertEquals(listOf("C", "A", "B"), s.visible().map { it.title })
    }

    @Test fun searchCoversTitleBodyAndTagsIgnoringCase() {
        s.add("Indkøb", "mælk")
        s.add("Andet", "", listOf("Privat"))
        s.add("Tredje", "BRØD")
        val all = s.visible()
        assertEquals(listOf("Indkøb"), all.search("indk").map { it.title })
        assertEquals(listOf("Andet"), all.search("privat").map { it.title })
        assertEquals(listOf("Tredje"), all.search("brød").map { it.title })
        assertEquals(3, all.search("  ").size)
    }
}
