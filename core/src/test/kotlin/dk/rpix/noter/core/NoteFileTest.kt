package dk.rpix.noter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Åbn og gem tekst- og Markdown-filer (afsnit 7 i docs/SPEC.md). */
class NoteFileTest {
    private fun content(title: String, body: String) = NoteFile.Content(title, body)

    // ---- Indlæsning ----

    @Test fun decodeUtf8() {
        assertEquals("Mælk 😀", NoteFile.decode("Mælk 😀".toByteArray(Charsets.UTF_8)))
        assertEquals("BOM fjernes", "æ", NoteFile.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "æ".toByteArray()))
        assertEquals("", NoteFile.decode(ByteArray(0)))
    }

    @Test fun decodeFallsBackToWindows1252() {
        assertEquals("Mælk", NoteFile.decode("Mælk".toByteArray(charset("windows-1252"))))
    }

    @Test fun decodeUtf16WithBom() {
        assertEquals("Æble", NoteFile.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Æble".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Æble", NoteFile.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Æble".toByteArray(Charsets.UTF_16BE)))
    }

    @Test fun binaryIsNotText() {
        assertNull(NoteFile.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)))
    }

    // ---- Titel og tekst ----

    @Test fun headingBecomesTitle() {
        assertEquals(content("Indkøb", "- Mælk\n- Brød"), NoteFile.parse("liste.md", "# Indkøb\n\n- Mælk\n- Brød\n"))
        assertEquals("også i .txt", content("Indkøb", "Mælk"), NoteFile.parse("liste.txt", "# Indkøb\nMælk"))
        assertEquals("kun overskrift", content("Indkøb", ""), NoteFile.parse("x.md", "# Indkøb\n"))
        assertEquals("mellemrum trimmes", content("Indkøb", ""), NoteFile.parse(null, "#   Indkøb  "))
    }

    @Test fun otherwiseFileNameBecomesTitle() {
        assertEquals(content("Indkøb", "Mælk\nBrød"), NoteFile.parse("Indkøb.txt", "Mælk\nBrød\n"))
        assertEquals("kun sidste endelse", content("noter.2026", "x"), NoteFile.parse("noter.2026.md", "x"))
        assertEquals("ingen endelse", content("README", "x"), NoteFile.parse("README", "x"))
        assertEquals("ukendt navn", content("", "x"), NoteFile.parse(null, "x"))
        assertEquals("## er ikke titel", content("a", "## Afsnit\ntekst"), NoteFile.parse("a.md", "## Afsnit\ntekst"))
        assertEquals("tom linje først", content("a", "\n# Titel"), NoteFile.parse("a.md", "\n# Titel"))
    }

    @Test fun lineEndingsAreNormalised() {
        assertEquals(content("T", "a\nb\nc"), NoteFile.parse(null, "# T\r\n\r\na\r\nb\rc\r\n"))
    }

    // ---- Gem ----

    @Test fun toMarkdownRoundTrips() {
        assertEquals("# Indkøb\n\n- Mælk\n", NoteFile.toMarkdown("Indkøb", "- Mælk"))
        assertEquals("uden titel", "- Mælk\n", NoteFile.toMarkdown("", "- Mælk"))
        assertEquals("uden tekst", "# Indkøb\n", NoteFile.toMarkdown("Indkøb", ""))
        for ((title, body) in listOf("Indkøb" to "- Mælk\n\n**fed**", "T" to "", "Æ, ø; å" to "linje 1\nlinje 2")) {
            assertEquals(content(title, body), NoteFile.parse("x.md", NoteFile.toMarkdown(title, body)))
        }
    }

    @Test fun fileName() {
        assertEquals("Indkøb.md", NoteFile.fileName("Indkøb", "md", "Note"))
        assertEquals("ulovlige tegn", "a b c d.txt", NoteFile.fileName("a/b\\c: d?*\"<>|", "txt", "Note"))
        assertEquals("tom titel", "Note.md", NoteFile.fileName("  ", "md", "Note"))
        assertEquals("punktum først", "skjult.md", NoteFile.fileName("..skjult", "md", "Note"))
        assertEquals("linjeskift", "a b.md", NoteFile.fileName("a\nb", "md", "Note"))
        val long = NoteFile.fileName("x".repeat(200), "md", "Note")
        assertEquals("højst 80 tegn før endelsen", 80 + 3, long.length)
        val emoji = NoteFile.fileName("x".repeat(79) + "😀", "md", "Note")
        assertEquals("intet halvt surrogatpar", "x".repeat(79) + ".md", emoji)
    }
}
