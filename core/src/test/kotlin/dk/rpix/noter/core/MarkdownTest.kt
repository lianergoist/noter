package dk.rpix.noter.core

import dk.rpix.noter.core.MdKind.BOLD
import dk.rpix.noter.core.MdKind.BULLET
import dk.rpix.noter.core.MdKind.CHECKBOX
import dk.rpix.noter.core.MdKind.CODE
import dk.rpix.noter.core.MdKind.DONE
import dk.rpix.noter.core.MdKind.HEADING
import dk.rpix.noter.core.MdKind.INDENT
import dk.rpix.noter.core.MdKind.ITALIC
import dk.rpix.noter.core.MdKind.LINK
import dk.rpix.noter.core.MdKind.MARKER
import dk.rpix.noter.core.MdKind.NUMBERED
import dk.rpix.noter.core.MdKind.QUOTE
import dk.rpix.noter.core.MdKind.TASK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---- hidden ----

    // Teksten med de skjulte tegn fjernet
    private fun shown(text: String): String {
        val h = Markdown.hidden(text)
        return text.indices.filter { i -> h.none { i >= it.start && i < it.end } }.map { text[it] }.joinToString("")
    }

    @Test fun hidden() {
        assertEquals("Indkøb", shown("# Indkøb"))
        assertEquals("", shown("##"))
        assertEquals("fed og kursiv og mere", shown("**fed** og *kursiv* og _mere_"))
        assertEquals("kode og *stjerne*", shown("`kode` og \\*stjerne\\*"))
        // Liste-, afkrydsnings- og citatmarkeringer bliver stående
        assertEquals("- fed\n* punkt\n1. tal\n- [ ] gøremål\n> citat", shown("- **fed**\n* punkt\n1. tal\n- [ ] gøremål\n> *citat*"))
        // Falske positiver skjules ikke
        assertEquals("snake_case 2 * 3 **ufærdig #tag C:\\sti", shown("snake_case 2 * 3 **ufærdig #tag C:\\sti"))
        assertEquals("https://a.dk/x_y_z", shown("https://a.dk/x_y_z"))
        assertTrue(Markdown.hidden("").isEmpty())
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

    // ---- Fortsæt liste ved Enter ----

    // Markøren (|) står lige efter det linjeskift, brugeren har indsat. "null" = ingen ændring.
    private fun cont(input: String): String {
        val cursor = input.indexOf('|')
        val r = Markdown.continueList(input.removeRange(cursor, cursor + 1), cursor) ?: return "null"
        assertEquals("tom markering", r.selStart, r.selEnd)
        return StringBuilder(r.text).insert(r.selStart, '|').toString()
    }

    @Test fun continueList() {
        assertEquals("- a\n- |", cont("- a\n|"))
        assertEquals("* a\n* |", cont("* a\n|"))
        assertEquals("+ a\n+ |", cont("+ a\n|"))
        assertEquals("indrykning bevares", "    - a\n    - |", cont("    - a\n|"))
        assertEquals("\t- a\n\t- |", cont("\t- a\n|"))
        assertEquals("1. a\n2. |", cont("1. a\n|"))
        assertEquals("9) a\n10) |", cont("9) a\n|"))
        assertEquals("   1. a\n   2. |", cont("   1. a\n|"))
        assertEquals("formatering i punktet", "- **a**\n- |", cont("- **a**\n|"))
        assertEquals("andre linjer urørt", "x\n- a\n- |\ny", cont("x\n- a\n|\ny"))
    }

    @Test fun enterInsideListItemSplitsIt() {
        assertEquals("- ab\n- |cd", cont("- ab\n|cd"))
        assertEquals("1. ab\n2. |cd", cont("1. ab\n|cd"))
        assertEquals("lige efter markeringen i et punkt med tekst", "- \n- |abc", cont("- \n|abc"))
    }

    @Test fun enterOnEmptyItemEndsList() {
        assertEquals("- a\n|", cont("- a\n- \n|"))
        assertEquals("|", cont("- \n|"))
        assertEquals("indrykket punkt", "|", cont("    - \n|"))
        assertEquals("1. a\n|", cont("1. a\n2. \n|"))
        assertEquals("kun blanktegn", "|", cont("-  \n|"))
        assertEquals("efterfølgende linjer urørt", "- a\n|\nb", cont("- a\n- \n|\nb"))
    }

    @Test fun enterOutsideListDoesNothing() {
        for (input in listOf("a\n|", "# a\n|", "-a\n|", "1.5 kg\n|", "\n|", "|", "- a|", "    tekst\n|")) {
            assertEquals(input, "null", cont(input))
        }
        assertEquals("før markeringen", "null", cont("\n|- a"))
        assertEquals("inde i markeringen", "null", cont("-\n| a"))
    }

    // ---- Links ----

    @Test fun links() {
        assertEquals("https://dr.dk", covered("Se https://dr.dk.", LINK))
        assertEquals(setOf(MdSpan(LINK, 3, 16)), spansOf("Se https://dr.dk.", LINK))
        assertEquals("http://a.dk/x?y=1&z=2#top", covered("http://a.dk/x?y=1&z=2#top", LINK))
        assertEquals("www.dr.dk/tv", covered("Gå til www.dr.dk/tv!", LINK))
        assertEquals("versaler", "HTTPS://DR.DK", covered("HTTPS://DR.DK", LINK))
        assertEquals("parentes i URL bevares", "https://a.dk/x_(y)", covered("(https://a.dk/x_(y))", LINK))
        assertEquals("tegnsætning til sidst", "https://a.dk", covered("\"https://a.dk\",", LINK))
        assertEquals("to links", "https://a.dkhttps://b.dk", covered("https://a.dk og https://b.dk", LINK))
        assertEquals("i et listepunkt", "https://a.dk", covered("- https://a.dk", LINK))
        assertEquals("æøå i stien", "https://a.dk/æble", covered("https://a.dk/æble", LINK))
    }

    @Test fun notLinks() {
        for (text in listOf("https://", "www.", "xhttps://a.dk", "ftp://a.dk", "mailto:a@b.dk", "javascript:alert(1)", "dr.dk")) {
            assertEquals(text, "", covered(text, LINK))
        }
    }

    @Test fun noFormattingInsideLinks() {
        assertEquals("", covered("https://a.dk/_x_/y", ITALIC))
        assertEquals("", covered("https://a.dk/_x_/y", MARKER))
        assertEquals("", covered("https://a.dk/*x*", ITALIC))
        assertEquals("https://a.dk", covered("**https://a.dk**", BOLD))
        assertEquals("https://a.dk", covered("**https://a.dk**", LINK))
        assertEquals("_x_ efter linket", "x", covered("https://a.dk _x_", ITALIC))
    }

    @Test fun urlAt() {
        val t = "Se https://dr.dk nu"
        assertEquals("https://dr.dk", Markdown.urlAt(t, 8, 8))
        assertEquals("i starten", "https://dr.dk", Markdown.urlAt(t, 3, 3))
        assertEquals("lige efter", "https://dr.dk", Markdown.urlAt(t, 16, 16))
        assertEquals("markering inden i", "https://dr.dk", Markdown.urlAt(t, 11, 16))
        assertNull("uden for", Markdown.urlAt(t, 1, 1))
        assertNull("markering ud over linket", Markdown.urlAt(t, 1, 10))
        assertEquals("www får https://", "https://www.dr.dk", Markdown.urlAt("www.dr.dk", 0, 0))
        assertNull(Markdown.urlAt("", 0, 0))
    }

    // ---- Citater ----

    @Test fun quotes() {
        assertEquals(setOf(MdSpan(QUOTE, 0, 7)), spansOf("> Hjut!", QUOTE))
        assertEquals("> ", covered("> Hjut!", MARKER))
        assertEquals("tom citatlinje", setOf(MdSpan(QUOTE, 0, 1)), spansOf(">", QUOTE))
        assertEquals("indrykket", setOf(MdSpan(QUOTE, 0, 7, 1)), spansOf("    > a", QUOTE))
        assertEquals("flere linjer", 2, spansOf("> a\n> b", QUOTE).size)
        assertEquals("formatering i citatet", "b", covered("> a **b**", BOLD))
        for (text in listOf(">a", "a > b", "->", "# > a")) {
            assertEquals(text, "", covered(text, QUOTE))
        }
    }

    @Test fun toggleQuote() {
        val op = Markdown::toggleQuote
        assertEquals("> a|", apply(op, "a|"))
        assertEquals("a|", apply(op, "> a|"))
        assertEquals("[> a\n\n> b]", apply(op, "[a\n\nb]"))
        assertEquals("[a\nb]", apply(op, "[> a\n> b]"))
        assertEquals("blandet bliver citat", "[> a\n> b]", apply(op, "[> a\nb]"))
        assertEquals("indrykning bevares", "    > a|", apply(op, "    a|"))
        assertEquals("listemarkering bliver stående", "> - a|", apply(op, "- a|"))
        assertEquals("markør i fjernet markering", "|a", apply(op, ">| a"))
        assertEquals("tom linje", "> |", apply(op, "|"))
    }

    @Test fun continueQuote() {
        assertEquals("> a\n> |", cont("> a\n|"))
        assertEquals("    > a\n    > |", cont("    > a\n|"))
        assertEquals("> ab\n> |cd", cont("> ab\n|cd"))
        assertEquals("tomt citat afslutter", "> a\n|", cont("> a\n> \n|"))
        assertEquals("|", cont(">\n|"))
    }

    // ---- Kode ----

    @Test fun code() {
        assertEquals(setOf(MdSpan(CODE, 1, 5)), spansOf("`hvor`", CODE))
        assertEquals("``", covered("`hvor`", MARKER))
        assertEquals("a b", covered("x `a b` y", CODE))
        assertEquals("to stykker", "ab", covered("`a` og `b`", CODE))
        assertEquals("dobbelte backticks", "a`b", covered("``a`b``", CODE))
        assertEquals("i et listepunkt", "x", covered("- `x`", CODE))
    }

    @Test fun noFormattingInsideCode() {
        assertEquals("", covered("`**a**`", BOLD))
        assertEquals("", covered("`_a_`", ITALIC))
        assertEquals("", covered("`https://a.dk`", LINK))
        assertEquals("backslash er tekst i kode", "``", covered("`a\\*b`", MARKER))
        assertEquals("fed uden om kode", "`a`", covered("**`a`**", BOLD))
    }

    @Test fun notCode() {
        for (text in listOf("`", "``", "`a", "a`", "``a`", "`a\nb`", "\\`a`")) {
            assertEquals(text, "", covered(text, CODE))
        }
    }

    @Test fun toggleCode() {
        val op = Markdown::toggleCode
        assertEquals("a `[b]` c", apply(op, "a [b] c"))
        assertEquals("a [b] c", apply(op, "a `[b]` c"))
        assertEquals("markeringerne med i markeringen", "a [b] c", apply(op, "a [`b`] c"))
        assertEquals("blanktegn udenfor", " `[b]` ", apply(op, "[ b ]"))
        assertEquals("tomt par", "a`|`", apply(op, "a|"))
        assertEquals("fjern tomt par", "a|", apply(op, "a`|`"))
    }

    // ---- Afkrydsningslister ----

    @Test fun tasks() {
        val t = "- [ ] Mælk"
        assertEquals(setOf(MdSpan(TASK, 0, 10)), spansOf(t, TASK))
        assertEquals(setOf(MdSpan(CHECKBOX, 2, 5, 0)), spansOf(t, CHECKBOX))
        assertEquals("- ", covered(t, MARKER))
        assertEquals("", covered(t, DONE))
        assertEquals("ikke også en punktliste", "", covered(t, BULLET))
        val done = "- [x] Brød"
        assertEquals(setOf(MdSpan(CHECKBOX, 2, 5, 1)), spansOf(done, CHECKBOX))
        assertEquals("Brød", covered(done, DONE))
        assertEquals("stort X", setOf(MdSpan(CHECKBOX, 2, 5, 1)), spansOf("* [X] a", CHECKBOX))
        assertEquals("indrykket", setOf(MdSpan(TASK, 0, 9, 1)), spansOf("    - [ ]", TASK))
        assertEquals("formatering i punktet", "b", covered("- [ ] a *b*", ITALIC))
        for (text in listOf("- [] a", "- [ ]a", "-[ ] a", "[ ] a", "- [y] a", "1. [ ] a")) {
            assertEquals(text, "", covered(text, TASK))
        }
        assertEquals("[] er en almindelig punktliste", 1, spansOf("- [] a", BULLET).size)
    }

    // Som apply, men markeringen skrives « ... », fordi teksterne indeholder [ ]
    private fun applySel(op: (String, Int, Int) -> MdEdit, input: String): String {
        val s = input.indexOf('«')
        val e = input.indexOf('»') - 1
        val r = op(input.replace("«", "").replace("»", ""), s, e)
        return StringBuilder(r.text).insert(r.selEnd, '»').insert(r.selStart, '«').toString()
    }

    @Test fun toggleTask() {
        val op = Markdown::toggleTask
        assertEquals("- [ ] a|", apply(op, "a|"))
        assertEquals("afkryds", "- [x] a|", apply(op, "- [ ] a|"))
        assertEquals("fjern kryds", "- [ ] a|", apply(op, "- [x] a|"))
        assertEquals("- [ ] a|", apply(op, "- [X] a|"))
        assertEquals("punktliste erstattes", "- [ ] a|", apply(op, "- a|"))
        assertEquals("nummereret erstattes", "- [ ] a|", apply(op, "1. a|"))
        assertEquals("indrykning bevares", "    - [ ] a|", apply(op, "    a|"))
        assertEquals("tom linje", "- [ ] |", apply(op, "|"))
        assertEquals("«- [ ] a\n\n- [ ] b»", applySel(op, "«a\n\nb»"))
        assertEquals("ét uafkrydset: kryds alle", "«- [x] a\n- [x] b»", applySel(op, "«- [x] a\n- [ ] b»"))
        assertEquals("alle afkrydset: fjern krydsene", "«- [ ] a\n- [ ] b»", applySel(op, "«- [x] a\n- [x] b»"))
        assertEquals("blandet bliver liste, kryds bevares", "«- [x] a\n- [ ] b»", applySel(op, "«- [x] a\nb»"))
    }

    @Test fun bulletsOnTasks() {
        assertEquals("afkrydsning bliver punktliste", "- a|", apply(Markdown::toggleBullets, "- [x] a|"))
        assertEquals("1. a|", apply(Markdown::toggleNumbers, "- [ ] a|"))
    }

    @Test fun continueTask() {
        assertEquals("- [ ] a\n- [ ] |", cont("- [ ] a\n|"))
        assertEquals("nyt punkt er uafkrydset", "- [x] a\n- [ ] |", cont("- [x] a\n|"))
        assertEquals("    * [ ] a\n    * [ ] |", cont("    * [ ] a\n|"))
        assertEquals("tomt punkt afslutter", "- [ ] a\n|", cont("- [ ] a\n- [ ] \n|"))
        assertEquals("|", cont("- [x]\n|"))
    }

    @Test fun plainNewKinds() {
        assertEquals("Hjut", Markdown.plain("> Hjut"))
        assertEquals("hvor", Markdown.plain("`hvor`"))
        assertEquals("☐ a\n☑ b", Markdown.plain("- [ ] a\n- [x] b"))
        assertEquals("    ☐ a", Markdown.plain("    - [ ] a"))
    }

    // ---- Værktøjslinjen: A (kursiv → fed → fed og kursiv → ingen) ----

    @Test fun cycleEmphasis() {
        val op = Markdown::cycleEmphasis
        assertEquals("a *[ord]* b", apply(op, "a [ord] b"))
        assertEquals("a **[ord]** b", apply(op, "a *[ord]* b"))
        assertEquals("a ***[ord]*** b", apply(op, "a **[ord]** b"))
        assertEquals("a [ord] b", apply(op, "a ***[ord]*** b"))
        assertEquals("markeringer med i markeringen", "a **[ord]** b", apply(op, "a [*ord*] b"))
        assertEquals("a [ord] b", apply(op, "a [***ord***] b"))
        assertEquals("blanktegn lægges udenfor", "a *[ord]* b", apply(op, "a[ ord ]b"))
        assertEquals("_ tæller ikke", "*[_ord_]*", apply(op, "[_ord_]"))
        assertEquals("a *|* b", apply(op, "a | b"))
        assertEquals("a **|** b", apply(op, "a *|* b"))
        assertEquals("a ***|*** b", apply(op, "a **|** b"))
        assertEquals("a | b", apply(op, "a ***|*** b"))
        assertEquals("første linje bestemmer", "**[a**\n\n**b]**", apply(op, "[*a*\n\nb]"))
        assertEquals("*[😀æ]*", apply(op, "[😀æ]"))
    }

    // ---- Links med tekst: [tekst](url) ----

    @Test fun textLinks() {
        val t = "Se [DavNotes](https://notes.rpix.dk/) her"
        assertEquals("DavNotes", covered(t, LINK))
        assertEquals("[](https://notes.rpix.dk/)", covered(t, MARKER))
        assertEquals("Se DavNotes her", shown(t))
        assertEquals("www", "w", covered("[w](www.a.dk)", LINK))
        assertEquals("parentes i URL", "W", covered("[W](https://a.org/wiki/A_(b)).", LINK))
        assertEquals("W.", shown("[W](https://a.org/wiki/A_(b))."))
        assertEquals("i et listepunkt", "- a", shown("- [a](https://a.dk)"))
        assertEquals("to links", "ab", covered("[a](https://a.dk) og [b](https://b.dk)", LINK))
    }

    @Test fun textLinkWithFormatting() {
        val t = "[**fed** link](https://a.dk)"
        assertEquals("**fed** link", covered(t, LINK))
        assertEquals("fed", covered(t, BOLD))
        assertEquals("fed link", shown(t))
        assertEquals("kursiv rundt om", "a [b](https://a.dk) c", covered("*a [b](https://a.dk) c*", ITALIC))
        assertEquals("`x` y", covered("[`x` y](https://a.dk)", LINK))
        assertEquals("x", covered("[`x` y](https://a.dk)", CODE))
        assertEquals("underscore i URL'en er ikke kursiv", "", covered("[a](https://a.dk/_x_)", ITALIC))
    }

    @Test fun notTextLinks() {
        // Kun den bare URL er et link
        for (text in listOf(
            "[a] (https://a.dk)", "[](https://a.dk)", "\\[a](https://a.dk)", "[a](https://a.dk/ b)", "[a\\](https://a.dk)",
        )) {
            assertEquals(text, "https://a.dk", covered(text, LINK).take(12))
            assertTrue(text, Markdown.hidden(text).none { text[it.start] == '[' })
        }
        assertEquals("", covered("[a](ftp://a.dk)", LINK))
        assertEquals("", covered("[a](mailto:a@a.dk)", LINK))
        assertEquals("", covered("`[a](https://a.dk)`", LINK))
        assertEquals("", covered("[a](https://)", LINK))
        assertEquals("[a](", shown("[a]("))
    }

    @Test fun textLinkPlainAndUrlAt() {
        assertEquals("Se her.", Markdown.plain("Se [her](https://a.dk)."))
        val t = "Se [her](https://a.dk) nu"
        assertEquals("i teksten", "https://a.dk", Markdown.urlAt(t, 5, 5))
        assertEquals("ved '['", "https://a.dk", Markdown.urlAt(t, 3, 3))
        assertEquals("i URL'en", "https://a.dk", Markdown.urlAt(t, 12, 12))
        assertEquals("lige efter", "https://a.dk", Markdown.urlAt(t, 22, 22))
        assertNull(Markdown.urlAt(t, 1, 1))
        assertEquals("https://www.a.dk", Markdown.urlAt("[w](www.a.dk)", 1, 1))
    }

    // ---- Værktøjslinjen: indsæt link ----

    @Test fun insertLink() {
        assertEquals(MdEdit("a [](), b", 3, 3), Markdown.insertLink("a , b", 2, 2))
        assertEquals("markeret tekst bliver linktekst", MdEdit("a [ord]() b", 8, 8), Markdown.insertLink("a ord b", 2, 5))
        assertEquals("blanktegn lægges udenfor", MdEdit("a [ord]() b", 8, 8), Markdown.insertLink("a ord b", 1, 6))
        assertEquals("markeret URL", MdEdit("[](https://a.dk)", 1, 1), Markdown.insertLink("https://a.dk", 0, 12))
        assertEquals("www", MdEdit("[](www.a.dk)", 1, 1), Markdown.insertLink("www.a.dk", 0, 8))
        assertEquals("flere linjer: kun ved markørens slutning", MdEdit("a\nb[]()", 4, 4), Markdown.insertLink("a\nb", 0, 3))
    }
}
