package dk.rpix.noter.core

import dk.rpix.noter.core.MdKind.BOLD
import dk.rpix.noter.core.MdKind.BULLET
import dk.rpix.noter.core.MdKind.HEADING
import dk.rpix.noter.core.MdKind.INDENT
import dk.rpix.noter.core.MdKind.ITALIC
import dk.rpix.noter.core.MdKind.MARKER
import dk.rpix.noter.core.MdKind.NUMBERED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Let formatering (afsnit 6 i docs/SPEC.md). */
class MarkdownTest {

    // Tegnene i teksten, som er dækket af mindst én span af den givne slags, i rækkefølge
    private fun covered(text: String, kind: MdKind): String {
        val spans = Markdown.spans(text).filter { it.kind == kind }
        return text.indices.filter { i -> spans.any { i >= it.start && i < it.end } }
            .map { text[it] }.joinToString("")
    }

    private fun spansOf(text: String, kind: MdKind) = Markdown.spans(text).filter { it.kind == kind }.toSet()

    // Markering skrives i teksten som [ ... ], markør som |. Teksterne her indeholder ikke selv de tegn.
    private fun apply(op: (String, Int, Int) -> MdEdit, input: String): String {
        val text: String
        val s: Int
        val e: Int
        if ('|' in input) {
            s = input.indexOf('|'); e = s
            text = input.removeRange(s, s + 1)
        } else {
            s = input.indexOf('[')
            val withoutOpen = input.removeRange(s, s + 1)
            e = withoutOpen.indexOf(']')
            text = withoutOpen.removeRange(e, e + 1)
        }
        val r = op(text, s, e)
        return if (r.selStart == r.selEnd) {
            StringBuilder(r.text).insert(r.selStart, '|').toString()
        } else {
            StringBuilder(r.text).insert(r.selEnd, ']').insert(r.selStart, '[').toString()
        }
    }

    // ---- Overskrifter ----

    @Test fun headings() {
        assertEquals(setOf(MdSpan(HEADING, 0, 7, 1)), spansOf("# Titel", HEADING))
        assertEquals("# ", covered("# Titel", MARKER))
        assertEquals(setOf(MdSpan(HEADING, 0, 7, 3)), spansOf("### Tre", HEADING))
        assertEquals(setOf(MdSpan(HEADING, 0, 11, 6)), spansOf("###### Seks", HEADING))
        assertEquals("tom overskrift mens man skriver", setOf(MdSpan(HEADING, 0, 1, 1)), spansOf("#", HEADING))
        assertEquals(setOf(MdSpan(HEADING, 2, 6, 2)), spansOf("a\n## b", HEADING))
    }

    @Test fun notHeadings() {
        assertEquals("hashtag", emptyList<MdSpan>(), Markdown.spans("#hashtag"))
        assertEquals("syv #", emptySet<MdSpan>(), spansOf("####### x", HEADING))
        assertEquals("indrykket", emptyList<MdSpan>(), Markdown.spans(" # x"))
        assertEquals(emptySet<MdSpan>(), spansOf("    # x", HEADING))
        assertEquals("", covered("    # x", MARKER))
        assertEquals("escapet", emptySet<MdSpan>(), spansOf("\\# ikke overskrift", HEADING))
        assertEquals("\\", covered("\\# ikke overskrift", MARKER))
    }

    @Test fun inlineInsideHeading() {
        assertEquals("fed", covered("# **fed** titel", BOLD))
        assertEquals("tilstødende markeringer slås sammen",
            setOf(MdSpan(MARKER, 0, 4), MdSpan(MARKER, 5, 7)), spansOf("# **a**", MARKER))
    }

    // ---- Lister og indrykning ----

    @Test fun bullets() {
        assertEquals(setOf(MdSpan(BULLET, 0, 6, 0)), spansOf("- Mælk", BULLET))
        assertEquals("- ", covered("- Mælk", MARKER))
        assertEquals(setOf(MdSpan(BULLET, 0, 3, 0)), spansOf("* a", BULLET))
        assertEquals(setOf(MdSpan(BULLET, 0, 3, 0)), spansOf("+ a", BULLET))
        assertEquals("tomt punkt mens man skriver", setOf(MdSpan(BULLET, 0, 2, 0)), spansOf("- ", BULLET))
        assertEquals(setOf(MdSpan(BULLET, 2, 5, 0)), spansOf("x\n- y", BULLET))
    }

    @Test fun notBullets() {
        assertEquals(emptySet<MdSpan>(), spansOf("-x", BULLET))
        assertEquals(emptySet<MdSpan>(), spansOf("---", BULLET))
        assertEquals(emptySet<MdSpan>(), spansOf("**fed** start", BULLET))
        assertEquals("fed", covered("**fed** start", BOLD))
        assertEquals(emptySet<MdSpan>(), spansOf("\\- ikke punkt", BULLET))
    }

    @Test fun bulletWithItalic() {
        assertEquals(setOf(MdSpan(BULLET, 0, 7, 0)), spansOf("* a *b*", BULLET))
        assertEquals("b", covered("* a *b*", ITALIC))
        assertEquals("* **", covered("* a *b*", MARKER))
        assertEquals(setOf(MdSpan(MARKER, 0, 3), MdSpan(MARKER, 4, 5)), spansOf("* *a*", MARKER))
    }

    @Test fun numbered() {
        assertEquals(setOf(MdSpan(NUMBERED, 0, 4, 0)), spansOf("1. a", NUMBERED))
        assertEquals("1. ", covered("1. a", MARKER))
        assertEquals(setOf(MdSpan(NUMBERED, 0, 5, 0)), spansOf("12) b", NUMBERED))
        assertEquals("12) ", covered("12) b", MARKER))
        assertEquals(emptyList<MdSpan>(), Markdown.spans("1.5 kg"))
        assertEquals("ti cifre", emptySet<MdSpan>(), spansOf("1234567890. x", NUMBERED))
    }

    @Test fun indentLevels() {
        assertEquals(setOf(MdSpan(BULLET, 0, 4, 0)), spansOf(" - x", BULLET))
        assertEquals(setOf(MdSpan(BULLET, 0, 5, 1)), spansOf("  - b", BULLET))
        assertEquals(setOf(MdSpan(BULLET, 0, 7, 1)), spansOf("    - b", BULLET))
        assertEquals("indrykning er ikke markering", "- ", covered("    - b", MARKER))
        assertEquals(setOf(MdSpan(BULLET, 0, 4, 1)), spansOf("\t- b", BULLET))
        assertEquals(setOf(MdSpan(BULLET, 0, 11, 2)), spansOf("        - c", BULLET))
        assertEquals(setOf(MdSpan(NUMBERED, 0, 7, 1)), spansOf("   1. a", NUMBERED))
    }

    @Test fun indentedText() {
        assertEquals(setOf(MdSpan(INDENT, 0, 9, 1)), spansOf("    tekst", INDENT))
        assertEquals(setOf(MdSpan(INDENT, 0, 7, 1)), spansOf("  tekst", INDENT))
        assertEquals(setOf(MdSpan(INDENT, 0, 6, 1)), spansOf("\ttekst", INDENT))
        assertEquals(setOf(MdSpan(INDENT, 0, 13, 2)), spansOf("        tekst", INDENT))
        assertEquals("ét mellemrum er niveau 0", emptyList<MdSpan>(), Markdown.spans(" tekst"))
        assertEquals("listepunkter er ikke INDENT", emptySet<MdSpan>(), spansOf("    - b", INDENT))
        assertEquals(setOf(MdSpan(INDENT, 0, 7, 1)), spansOf("    # x", INDENT))
    }

    // ---- Fed og kursiv ----

    @Test fun boldAndItalic() {
        assertEquals("fed", covered("a **fed** b", BOLD))
        assertEquals("****", covered("a **fed** b", MARKER))
        assertEquals("", covered("a **fed** b", ITALIC))
        assertEquals("kursiv", covered("*kursiv*", ITALIC))
        assertEquals("kursiv", covered("_kursiv_", ITALIC))
        assertEquals("inde i ord med *", "b", covered("a*b*c", ITALIC))
        assertEquals("ab", covered("**a** og **b**", BOLD))
        assertEquals("a", covered("(_a_)", ITALIC))
        assertEquals("😀æ", covered("**😀æ**", BOLD))
    }

    @Test fun nesting() {
        assertEquals("begge", covered("***begge***", BOLD))
        assertEquals("kursiv yderst", "**begge**", covered("***begge***", ITALIC))
        assertEquals(setOf(MdSpan(MARKER, 0, 3), MdSpan(MARKER, 8, 11)), spansOf("***begge***", MARKER))

        assertEquals("fed *kursiv* fed", covered("**fed *kursiv* fed**", BOLD))
        assertEquals("kursiv", covered("**fed *kursiv* fed**", ITALIC))
        assertEquals("******", covered("**fed *kursiv* fed**", MARKER))

        assertEquals("kursiv **fed** kursiv", covered("*kursiv **fed** kursiv*", ITALIC))
        assertEquals("fed", covered("*kursiv **fed** kursiv*", BOLD))
    }

    @Test fun falsePositives() {
        for (text in listOf(
            "snake_case_navn", "2 * 3 * 4", "** x**", "**x **", "****", "**", "**ufærdig", "*a", "_a_b",
            "*start\nslut*", "**start\nslut**",
        )) {
            assertEquals(text, emptyList<MdSpan>(), Markdown.spans(text).filter { it.kind in setOf(BOLD, ITALIC, MARKER) })
        }
    }

    @Test fun escapes() {
        assertEquals("", covered("\\*ikke\\*", ITALIC))
        assertEquals("\\\\", covered("\\*ikke\\*", MARKER))
        assertEquals("backslash foran bogstav er tekst", emptyList<MdSpan>(), Markdown.spans("C:\\sti"))
        assertEquals("escapet backslash", "a", covered("\\\\*a*", ITALIC))
        assertEquals(setOf(MdSpan(MARKER, 0, 1), MdSpan(MARKER, 2, 3), MdSpan(MARKER, 4, 5)), spansOf("\\\\*a*", MARKER))
    }

    @Test fun plainTextHasNoSpans() {
        assertEquals(emptyList<MdSpan>(), Markdown.spans(""))
        assertEquals(emptyList<MdSpan>(), Markdown.spans("Mælk\nBrød, kaffe; 100% (ikke *)"))
    }

    @Test fun spansStayWithinText() {
        val text = "# A\n- **b** _c_\n    1. d\n\\*e"
        for (s in Markdown.spans(text)) {
            assertTrue("$s", s.start in 0 until s.end && s.end <= text.length)
        }
    }

    // ---- plain ----

    @Test fun plain() {
        assertEquals("Indkøb", Markdown.plain("# Indkøb"))
        assertEquals("fed og kursiv og mere", Markdown.plain("**fed** og *kursiv* og _mere_"))
        assertEquals("• Mælk\n  • Brød\n1. Kaffe", Markdown.plain("- Mælk\n  * Brød\n1. Kaffe"))
        assertEquals("snake_case 2 * 3", Markdown.plain("snake_case 2 * 3"))
        assertEquals("*stjerne*", Markdown.plain("\\*stjerne\\*"))
        assertEquals("C:\\sti", Markdown.plain("C:\\sti"))
        assertEquals("", Markdown.plain(""))
    }

    // ---- Værktøjslinjen: fed og kursiv ----

    @Test fun toggleBold() {
        val op = Markdown::toggleBold
        assertEquals("a **[fed]** b", apply(op, "a [fed] b"))
        assertEquals("a [fed] b", apply(op, "a **[fed]** b"))
        assertEquals("markeringer med i markeringen", "a [fed] b", apply(op, "a [**fed**] b"))
        assertEquals("blanktegn lægges udenfor", "a **[fed]** b", apply(op, "a[ fed ]b"))
        assertEquals("a **|** b", apply(op, "a | b"))
        assertEquals("tomt par fjernes", "a | b", apply(op, "a **|** b"))
        assertEquals("flere linjer", "**[a**\n\n**b]**", apply(op, "[a\n\nb]"))
        assertEquals("***[fed]***", apply(op, "*[fed]*"))
        assertEquals("*[fed]*", apply(op, "***[fed]***"))
        assertEquals("**[😀æ]**", apply(op, "[😀æ]"))
    }

    @Test fun toggleItalic() {
        val op = Markdown::toggleItalic
        assertEquals("a *[kursiv]* b", apply(op, "a [kursiv] b"))
        assertEquals("a [kursiv] b", apply(op, "a *[kursiv]* b"))
        assertEquals("fed er ikke kursiv", "***[fed]***", apply(op, "**[fed]**"))
        assertEquals("**[fed]**", apply(op, "***[fed]***"))
        assertEquals("*|*", apply(op, "|"))
        assertEquals("|", apply(op, "*|*"))
    }

    // ---- Værktøjslinjen: linjefunktioner ----

    @Test fun cycleHeading() {
        val op = Markdown::cycleHeading
        assertEquals("# Ind|køb", apply(op, "Ind|køb"))
        assertEquals("## Ind|køb", apply(op, "# Ind|køb"))
        assertEquals("### Ind|køb", apply(op, "## Ind|køb"))
        assertEquals("Ind|køb", apply(op, "### Ind|køb"))
        assertEquals("listemarkering erstattes", "# pu|nkt", apply(op, "- pu|nkt"))
        assertEquals("hele linjer markeres", "a\n[# b\n# c]\nd", apply(op, "a\n[b\nc]\nd"))
        assertEquals("første linje bestemmer", "[## a\n## b]", apply(op, "[# a\nb]"))
        assertEquals("tomme linjer springes over", "[# a\n\n# b]", apply(op, "[a\n\nb]"))
        assertEquals("delvis markering giver hele linjen", "[# abc]", apply(op, "a[b]c"))
        assertEquals("markør i fjernet markering", "## |x", apply(op, "#| x"))
    }

    @Test fun toggleBullets() {
        val op = Markdown::toggleBullets
        assertEquals("[- a\n- b]", apply(op, "[a\nb]"))
        assertEquals("[a\nb]", apply(op, "[- a\n- b]"))
        assertEquals("blandet bliver liste", "[- a\n- b]", apply(op, "[- a\nb]"))
        assertEquals("[- a\n\n- b]", apply(op, "[a\n\nb]"))
        assertEquals("indrykning bevares", "    - a|", apply(op, "    a|"))
        assertEquals("nummereret erstattes", "- a|", apply(op, "1. a|"))
        assertEquals("overskrift erstattes", "- a|", apply(op, "# a|"))
        assertEquals("|a", apply(op, "- |a"))
        assertEquals("markør i fjernet markering", "|a", apply(op, "-| a"))
        assertEquals("andre linjer urørt", "x\n- y|\nz", apply(op, "x\ny|\nz"))
        assertEquals("start en liste på en tom linje", "a\n- |", apply(op, "a\n|"))
    }

    @Test fun toggleNumbers() {
        val op = Markdown::toggleNumbers
        assertEquals("[1. a\n2. b\n3. c]", apply(op, "[a\nb\nc]"))
        assertEquals("[a\nb]", apply(op, "[1. a\n2. b]"))
        assertEquals("punktliste erstattes", "[1. a\n2. b]", apply(op, "[- a\n- b]"))
        assertEquals("pr. niveau", "[1. a\n    1. b\n    2. c\n2. d]", apply(op, "[a\n    b\n    c\nd]"))
        assertEquals("dybere niveau starter forfra",
            "[1. a\n    1. b\n2. c\n    1. d]", apply(op, "[a\n    b\nc\n    d]"))
        assertEquals("1. a|", apply(op, "a|"))
    }

    @Test fun indentAndOutdent() {
        assertEquals("    a|", apply(Markdown::indent, "a|"))
        assertEquals("[    a\n\n    b]", apply(Markdown::indent, "[a\n\nb]"))
        assertEquals("    - a|", apply(Markdown::indent, "- a|"))
        assertEquals("[    abc]", apply(Markdown::indent, "a[b]c"))

        assertEquals("a|", apply(Markdown::outdent, "    a|"))
        assertEquals("a|", apply(Markdown::outdent, "  a|"))
        assertEquals("  a|", apply(Markdown::outdent, "      a|"))
        assertEquals("a|", apply(Markdown::outdent, "\ta|"))
        assertEquals("uændret", "a|", apply(Markdown::outdent, "a|"))
        assertEquals("markør i fjernet indrykning", "|a", apply(Markdown::outdent, "  |  a"))
        assertEquals("[- a\nb]", apply(Markdown::outdent, "[    - a\n    b]"))
    }
}
