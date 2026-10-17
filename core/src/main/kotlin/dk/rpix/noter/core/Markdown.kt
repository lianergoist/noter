package dk.rpix.noter.core

/** Slags formatering. Se afsnit 6 i docs/SPEC.md. */
enum class MdKind { HEADING, BULLET, NUMBERED, INDENT, BOLD, ITALIC, MARKER, LINK, QUOTE, TASK, CHECKBOX, DONE, CODE }

/** Et stykke formatering i teksten: UTF-16-indeks, [start] inklusiv, [end] eksklusiv. */
data class MdSpan(val kind: MdKind, val start: Int, val end: Int, val level: Int = 0)

/** Resultatet af en knap på værktøjslinjen: ny tekst og ny markering. */
data class MdEdit(val text: String, val selStart: Int, val selEnd: Int)

/**
 * Let formatering (Markdown-delmængde) af noteteksten. Kun visning: teksten selv gemmes uændret.
 * Alt gælder linje for linje; inline-formatering krydser aldrig et linjeskift.
 */
object Markdown {
    private const val PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private const val INDENT_STEP = "    "

    private enum class Prefix { NONE, HEADING, BULLET, NUMBERED, QUOTE, TASK }

    /** Én linje: [indentEnd] er første ikke-blanke tegn, [markerEnd] hvor indholdet efter præfikset starter. */
    private class Line(
        val start: Int, val end: Int, val indentEnd: Int, val level: Int,
        val prefix: Prefix, val markerEnd: Int, val headingLevel: Int = 0, val checked: Boolean = false,
    ) {
        /** Afkrydsningsfeltets '[' (kun TASK): efter "- ". */
        val box get() = indentEnd + 2
    }

    /** En ændring af teksten: erstat [del] tegn fra [pos] med [ins]. */
    private class Change(val pos: Int, val del: Int, val ins: String)

    private fun lineAt(text: String, start: Int): Line {
        var end = text.indexOf('\n', start)
        if (end < 0) end = text.length
        var i = start
        var cols = 0
        while (i < end && (text[i] == ' ' || text[i] == '\t')) {
            cols += if (text[i] == '\t') 4 else 1
            i++
        }
        val level = (cols + 2) / 4
        // Overskrift: 1-6 '#' i linjens start, så mellemrum eller linjeslut
        if (i == start) {
            var n = 0
            while (start + n < end && text[start + n] == '#') n++
            if (n in 1..6 && (start + n == end || text[start + n] == ' ')) {
                return Line(start, end, i, 0, Prefix.HEADING, minOf(start + n + 1, end), n)
            }
        }
        // Citat: '>' efter eventuel indrykning, så mellemrum eller linjeslut
        if (i < end && text[i] == '>' && (i + 1 == end || text[i + 1] == ' ')) {
            return Line(start, end, i, level, Prefix.QUOTE, minOf(i + 2, end))
        }
        if (i + 1 < end && text[i] in "-*+" && text[i + 1] == ' ') {
            // Afkrydsning: "- [ ] ", "- [x] " (også ved linjeslut)
            val b = i + 2
            if (b + 2 < end && text[b] == '[' && text[b + 1] in " xX" && text[b + 2] == ']' &&
                (b + 3 == end || text[b + 3] == ' ')
            ) {
                return Line(start, end, i, level, Prefix.TASK, minOf(b + 4, end), checked = text[b + 1] != ' ')
            }
            return Line(start, end, i, level, Prefix.BULLET, i + 2)
        }
        var d = i
        while (d < end && text[d] in '0'..'9') d++
        if (d - i in 1..9 && d + 1 < end && (text[d] == '.' || text[d] == ')') && text[d + 1] == ' ') {
            return Line(start, end, i, level, Prefix.NUMBERED, d + 2)
        }
        return Line(start, end, i, level, Prefix.NONE, i)
    }

    private fun lines(text: String): List<Line> {
        val out = mutableListOf<Line>()
        var start = 0
        while (true) {
            val line = lineAt(text, start)
            out += line
            if (line.end >= text.length) return out
            start = line.end + 1
        }
    }

    fun spans(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val markers = mutableListOf<IntArray>()
        for (l in lines(text)) {
            when (l.prefix) {
                Prefix.HEADING -> out += MdSpan(MdKind.HEADING, l.start, l.end, l.headingLevel)
                Prefix.BULLET -> out += MdSpan(MdKind.BULLET, l.start, l.end, l.level)
                Prefix.NUMBERED -> out += MdSpan(MdKind.NUMBERED, l.start, l.end, l.level)
                Prefix.QUOTE -> out += MdSpan(MdKind.QUOTE, l.start, l.end, l.level)
                Prefix.TASK -> {
                    out += MdSpan(MdKind.TASK, l.start, l.end, l.level)
                    out += MdSpan(MdKind.CHECKBOX, l.box, l.box + 3, if (l.checked) 1 else 0)
                    if (l.checked && l.markerEnd < l.end) out += MdSpan(MdKind.DONE, l.markerEnd, l.end)
                }
                Prefix.NONE -> if (l.level >= 1 && l.indentEnd < l.end) out += MdSpan(MdKind.INDENT, l.start, l.end, l.level)
            }
            when (l.prefix) {
                Prefix.NONE -> {}
                // Afkrydsningsfeltet vises for sig, kun "- " dæmpes
                Prefix.TASK -> markers += intArrayOf(l.indentEnd, l.box)
                else -> markers += intArrayOf(l.indentEnd, l.markerEnd)
            }
            inline(text, l.markerEnd, l.end, out, markers)
        }
        // Tilstødende markeringer slås sammen
        var cur: IntArray? = null
        for (m in markers.sortedBy { it[0] }) {
            if (cur != null && m[0] <= cur[1]) {
                cur[1] = maxOf(cur[1], m[1])
            } else {
                cur?.let { out += MdSpan(MdKind.MARKER, it[0], it[1]) }
                cur = m.copyOf()
            }
        }
        cur?.let { out += MdSpan(MdKind.MARKER, it[0], it[1]) }
        return out
    }

    private fun isPunct(c: Char) = !c.isLetterOrDigit() && !c.isWhitespace()

    private val URL = Regex("""\b(?:https?://|www\.)[^\s<>]+""", RegexOption.IGNORE_CASE)
    private const val URL_TRAILING = ".,;:!?'\"*_"

    /** URL'er i [from, to) som [start, slut]. Tegnsætning til sidst og en ulige ')' eller ']' hører ikke med. */
    private fun urls(text: String, from: Int, to: Int): List<IntArray> {
        val out = mutableListOf<IntArray>()
        for (m in URL.findAll(text.substring(from, to))) {
            val start = from + m.range.first
            var end = from + m.range.last + 1
            while (end > start) {
                val c = text[end - 1]
                val unbalanced = (c == ')' && text.count(start, end, ')') > text.count(start, end, '(')) ||
                    (c == ']' && text.count(start, end, ']') > text.count(start, end, '['))
                if (c in URL_TRAILING || unbalanced) end-- else break
            }
            val prefix = if (text.startsWith("www.", start, ignoreCase = true)) 4 else text.indexOf("//", start) + 2 - start
            if (end - start > prefix) out += intArrayOf(start, end)
        }
        return out
    }

    private fun String.count(from: Int, to: Int, c: Char) = (from until to).count { this[it] == c }

    private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
        for (i in from until length) if (pred(this[i])) return i
        return length
    }

    /** En række ens '*' eller '_'. Som opener bruges tegn fra højre ende, som closer fra venstre. */
    private class Run(val ch: Char, var start: Int, var len: Int, val orig: Int, val open: Boolean, val close: Boolean)

    /**
     * Kode i [from, to) som [start, slut] inkl. backticks: et løb af n backticks lukkes af det næste løb af
     * præcis n. Escape gælder uden for kode, så \` er en bogstavelig backtick.
     */
    private fun codeSpans(text: String, from: Int, to: Int): List<IntArray> {
        val out = mutableListOf<IntArray>()
        var p = from
        while (p < to) {
            val c = text[p]
            if (c == '\\' && p + 1 < to && text[p + 1] in PUNCT) {
                p += 2
                continue
            }
            if (c != '`') { p++; continue }
            var q = p
            while (q < to && text[q] == '`') q++
            val n = q - p
            var r = q
            var close = -1
            while (r < to) {
                if (text[r] != '`') { r++; continue }
                var rq = r
                while (rq < to && text[rq] == '`') rq++
                if (rq - r == n) { close = r; break }
                r = rq
            }
            if (close > q) {
                out += intArrayOf(p, close + n)
                p = close + n
            } else {
                p = q
            }
        }
        return out
    }

    /** Fed og kursiv i [from, to) efter en forenklet udgave af CommonMarks delimiter-algoritme. */
    private fun inline(text: String, from: Int, to: Int, out: MutableList<MdSpan>, markers: MutableList<IntArray>) {
        val runs = mutableListOf<Run>()
        // Kode og URL'er er uigennemsigtige: ingen fed, kursiv eller escape inden i dem
        val code = codeSpans(text, from, to)
        for (c in code) {
            val n = text.indexOfFirst(c[0]) { it != '`' } - c[0]
            out += MdSpan(MdKind.CODE, c[0] + n, c[1] - n)
            markers += intArrayOf(c[0], c[0] + n)
            markers += intArrayOf(c[1] - n, c[1])
        }
        val links = urls(text, from, to).filter { l -> code.none { l[0] < it[1] && it[0] < l[1] } }
        for (l in links) out += MdSpan(MdKind.LINK, l[0], l[1])
        val opaque = (code + links).sortedBy { it[0] }
        var li = 0
        var p = from
        while (p < to) {
            if (li < opaque.size && p >= opaque[li][0]) {
                p = maxOf(p, opaque[li][1])
                li++
                continue
            }
            val c = text[p]
            if (c == '\\' && p + 1 < to && text[p + 1] in PUNCT) {
                markers += intArrayOf(p, p + 1)
                p += 2
                continue
            }
            if (c == '*' || c == '_') {
                var q = p
                while (q < to && text[q] == c) q++
                // Linjens kanter tæller som blanktegn
                val before = if (p > 0 && text[p - 1] != '\n') text[p - 1] else ' '
                val after = if (q < text.length && text[q] != '\n') text[q] else ' '
                val bWs = before.isWhitespace()
                val aWs = after.isWhitespace()
                val bP = isPunct(before)
                val aP = isPunct(after)
                val left = !aWs && (!aP || bWs || bP)
                val right = !bWs && (!bP || aWs || aP)
                val open = if (c == '*') left else left && (!right || bP)
                val close = if (c == '*') right else right && (!left || aP)
                runs += Run(c, p, q - p, q - p, open, close)
                p = q
                continue
            }
            p++
        }

        var ci = 0
        while (ci < runs.size) {
            val c = runs[ci]
            if (!c.close || c.len == 0) { ci++; continue }
            var found = -1
            for (oi in ci - 1 downTo 0) {
                val o = runs[oi]
                if (o.ch != c.ch || !o.open || o.len == 0) continue
                // "Reglen om 3" fra CommonMark
                val both = o.close || c.open
                if (both && (o.orig + c.orig) % 3 == 0 && !(o.orig % 3 == 0 && c.orig % 3 == 0)) continue
                found = oi
                break
            }
            if (found < 0) { ci++; continue }
            val o = runs[found]
            val use = if (o.len >= 2 && c.len >= 2) 2 else 1
            val openEnd = o.start + o.len
            out += MdSpan(if (use == 2) MdKind.BOLD else MdKind.ITALIC, openEnd, c.start)
            markers += intArrayOf(openEnd - use, openEnd)
            markers += intArrayOf(c.start, c.start + use)
            o.len -= use
            c.start += use
            c.len -= use
            for (k in found + 1 until ci) runs[k].len = 0
            if (c.len == 0) ci++
        }
    }

    fun plain(text: String): String = lines(text).joinToString("\n") { l ->
        val inlineMarkers = mutableListOf<IntArray>()
        inline(text, l.markerEnd, l.end, mutableListOf(), inlineMarkers)
        val sb = StringBuilder()
        sb.append(text, l.start, l.indentEnd)
        when (l.prefix) {
            Prefix.BULLET -> sb.append("• ")
            Prefix.NUMBERED -> sb.append(text, l.indentEnd, l.markerEnd)
            Prefix.TASK -> sb.append(if (l.checked) "☑ " else "☐ ")
            else -> {}
        }
        for (i in l.markerEnd until l.end) {
            if (inlineMarkers.none { i >= it[0] && i < it[1] }) sb.append(text[i])
        }
        sb.toString()
    }

    // ---- Værktøjslinjen ----

    private fun applyChanges(text: String, changes: List<Change>): String {
        val sb = StringBuilder()
        var last = 0
        for (c in changes) {
            sb.append(text, last, c.pos).append(c.ins)
            last = c.pos + c.del
        }
        return sb.append(text, last, text.length).toString()
    }

    /**
     * Hvor position [p] havner efter [changes] (sorteret, ikke overlappende). Står [p] i et fjernet stykke,
     * havner den lige efter det indsatte. [stickLeft]: en indsættelse præcis ved [p] lægges efter [p].
     */
    private fun mapPos(changes: List<Change>, p: Int, stickLeft: Boolean = false): Int {
        var d = 0
        for (c in changes) {
            val cEnd = c.pos + c.del
            if (cEnd < p || (cEnd == p && !(stickLeft && c.del == 0))) {
                d += c.ins.length - c.del
            } else if (c.pos <= p && p < cEnd) {
                return c.pos + d + c.ins.length
            }
        }
        return p + d
    }

    private fun runBefore(text: String, p: Int, ch: Char): Int {
        var i = p
        while (i > 0 && text[i - 1] == ch) i--
        return p - i
    }

    private fun runAfter(text: String, p: Int, ch: Char): Int {
        var i = p
        while (i < text.length && text[i] == ch) i++
        return i - p
    }

    /**
     * Er et løb af længden [run] en markering for fed ([n] = 2) eller kursiv ([n] = 1) med '*',
     * eller for kode med '`' (præcis én)?
     */
    private fun present(ch: Char, run: Int, n: Int) = when {
        ch == '`' -> run == 1
        n == 2 -> run >= 2
        else -> run == 1 || run == 3
    }

    private fun toggleInline(text: String, s: Int, e: Int, ch: Char, n: Int): MdEdit {
        val mark = ch.toString().repeat(n)
        val present = { run: Int -> present(ch, run, n) }
        val before = { p: Int -> runBefore(text, p, ch) }
        val after = { p: Int -> runAfter(text, p, ch) }
        if (s == e) {
            if (present(minOf(before(s), after(s)))) {
                return MdEdit(text.removeRange(s, s + n).removeRange(s - n, s), s - n, s - n)
            }
            return MdEdit(text.substring(0, s) + mark + mark + text.substring(s), s + n, s + n)
        }
        // Den ikke-blanke del af hver linje i markeringen
        val segs = mutableListOf<IntArray>()
        var ls = s
        while (true) {
            var le = text.indexOf('\n', ls)
            if (le < 0 || le > e) le = e
            var a = ls
            var b = le
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            if (a < b) segs += intArrayOf(a, b)
            if (le >= e) break
            ls = le + 1
        }
        if (segs.isEmpty()) return MdEdit(text, s, e)
        // 1 = markeringerne er med i markeringen, 2 = de står lige udenfor, 0 = ingen
        val states = segs.map { (a, b) ->
            val l = after(a)
            val r = before(b)
            when {
                l + r < b - a && present(minOf(l, r)) -> 1
                present(minOf(before(a), after(b))) -> 2
                else -> 0
            }
        }
        val remove = states.all { it != 0 }
        val changes = mutableListOf<Change>()
        segs.forEachIndexed { i, (a, b) ->
            when {
                remove && states[i] == 1 -> { changes += Change(a, n, ""); changes += Change(b - n, n, "") }
                remove && states[i] == 2 -> { changes += Change(a - n, n, ""); changes += Change(b, n, "") }
                !remove && states[i] == 0 -> { changes += Change(a, 0, mark); changes += Change(b, 0, mark) }
            }
        }
        val first = segs.first()
        val last = segs.last()
        val contentStart = if (remove && states.first() == 1) first[0] + n else first[0]
        val contentEnd = if (remove && states.last() == 1) last[1] - n else last[1]
        return MdEdit(applyChanges(text, changes), mapPos(changes, contentStart), mapPos(changes, contentEnd, stickLeft = true))
    }

    fun toggleBold(text: String, selStart: Int, selEnd: Int): MdEdit = toggleInline(text, selStart, selEnd, '*', 2)

    fun toggleItalic(text: String, selStart: Int, selEnd: Int): MdEdit = toggleInline(text, selStart, selEnd, '*', 1)

    fun toggleCode(text: String, selStart: Int, selEnd: Int): MdEdit = toggleInline(text, selStart, selEnd, '`', 1)

    /**
     * Fælles for linjefunktionerne: [change] får linjerne under markeringen (tomme linjer udeladt, når der er
     * flere) og returnerer ændringerne. Tom markering: markøren følger teksten. Ellers markeres hele linjerne.
     */
    private fun editLines(text: String, s: Int, e: Int, change: (List<Line>) -> List<Change>): MdEdit {
        // En markering, der slutter lige efter et linjeskift, omfatter ikke den næste linje
        val last = if (e > s && text[e - 1] == '\n') e - 1 else e
        val all = mutableListOf<Line>()
        var start = text.lastIndexOf('\n', s - 1) + 1
        while (true) {
            val l = lineAt(text, start)
            all += l
            if (l.end >= last) break
            start = l.end + 1
        }
        val lines = if (all.size == 1) all else all.filter { text.substring(it.start, it.end).isNotBlank() }
        val changes = change(lines).sortedBy { it.pos }
        val newText = applyChanges(text, changes)
        if (s == e) {
            val p = mapPos(changes, s)
            return MdEdit(newText, p, p)
        }
        val delta = changes.sumOf { it.ins.length - it.del }
        return MdEdit(newText, all.first().start, all.last().end + delta)
    }

    fun cycleHeading(text: String, selStart: Int, selEnd: Int): MdEdit = editLines(text, selStart, selEnd) { lines ->
        val first = lines.firstOrNull() ?: return@editLines emptyList()
        val h = if (first.prefix == Prefix.HEADING) first.headingLevel else 0
        val next = if (h < 3) h + 1 else 0
        lines.mapNotNull { l ->
            when {
                next > 0 -> Change(l.start, l.markerEnd - l.start, "#".repeat(next) + " ")
                l.prefix == Prefix.HEADING -> Change(l.start, l.markerEnd - l.start, "")
                else -> null
            }
        }
    }

    fun toggleBullets(text: String, selStart: Int, selEnd: Int): MdEdit = editLines(text, selStart, selEnd) { lines ->
        if (lines.all { it.prefix == Prefix.BULLET }) {
            lines.map { Change(it.indentEnd, it.markerEnd - it.indentEnd, "") }
        } else {
            lines.filter { it.prefix != Prefix.BULLET }.map { Change(it.indentEnd, it.markerEnd - it.indentEnd, "- ") }
        }
    }

    fun toggleNumbers(text: String, selStart: Int, selEnd: Int): MdEdit = editLines(text, selStart, selEnd) { lines ->
        if (lines.all { it.prefix == Prefix.NUMBERED }) {
            lines.map { Change(it.indentEnd, it.markerEnd - it.indentEnd, "") }
        } else {
            // Tæller pr. indrykningsniveau; dybere niveauer starter forfra efter et mindre indrykket punkt
            val counters = mutableMapOf<Int, Int>()
            lines.map { l ->
                counters.keys.removeAll { it > l.level }
                val n = (counters[l.level] ?: 0) + 1
                counters[l.level] = n
                Change(l.indentEnd, l.markerEnd - l.indentEnd, "$n. ")
            }
        }
    }

    fun toggleQuote(text: String, selStart: Int, selEnd: Int): MdEdit = editLines(text, selStart, selEnd) { lines ->
        if (lines.all { it.prefix == Prefix.QUOTE }) {
            lines.map { Change(it.indentEnd, it.markerEnd - it.indentEnd, "") }
        } else {
            // Citatet lægges uden om linjen; en listemarkering bliver stående som tekst
            lines.filter { it.prefix != Prefix.QUOTE }.map { Change(it.indentEnd, 0, "> ") }
        }
    }

    /**
     * Linjer, der ikke er afkrydsningspunkter, bliver det ("- [ ] "). Er alle allerede punkter, krydses de af,
     * eller krydset fjernes, hvis alle var afkrydset.
     */
    fun toggleTask(text: String, selStart: Int, selEnd: Int): MdEdit = editLines(text, selStart, selEnd) { lines ->
        if (lines.all { it.prefix == Prefix.TASK }) {
            val mark = if (lines.all { it.checked }) " " else "x"
            lines.map { Change(it.box + 1, 1, mark) }
        } else {
            lines.filter { it.prefix != Prefix.TASK }.map { Change(it.indentEnd, it.markerEnd - it.indentEnd, "- [ ] ") }
        }
    }

    fun indent(text: String, selStart: Int, selEnd: Int): MdEdit = editLines(text, selStart, selEnd) { lines ->
        lines.map { Change(it.start, 0, INDENT_STEP) }
    }

    fun outdent(text: String, selStart: Int, selEnd: Int): MdEdit = editLines(text, selStart, selEnd) { lines ->
        lines.mapNotNull { l ->
            var n = 0
            if (l.start < l.end && text[l.start] == '\t') {
                n = 1
            } else {
                while (n < INDENT_STEP.length && l.start + n < l.end && text[l.start + n] == ' ') n++
            }
            if (n == 0) null else Change(l.start, n, "")
        }
    }

    /**
     * Enter i en liste eller et citat. [cursor] står lige efter det linjeskift, brugeren har indsat. Var linjen før
     * et listepunkt eller et citat, får den nye linje samme indrykning og markering (næste nummer, uafkrydset). Var punktet tomt, og er den nye linje også tom,
     * fjernes punktet i stedet, og listen slutter. Ellers null (intet at gøre).
     */
    fun continueList(text: String, cursor: Int): MdEdit? {
        if (cursor == 0 || text[cursor - 1] != '\n') return null
        val prev = lineAt(text, text.lastIndexOf('\n', cursor - 2) + 1)
        if (prev.prefix !in setOf(Prefix.BULLET, Prefix.NUMBERED, Prefix.TASK, Prefix.QUOTE)) return null
        var nextEnd = text.indexOf('\n', cursor)
        if (nextEnd < 0) nextEnd = text.length
        if (text.substring(prev.markerEnd, prev.end).isBlank() && text.substring(cursor, nextEnd).isBlank()) {
            return MdEdit(text.removeRange(prev.start, cursor), prev.start, prev.start)
        }
        val marker = when (prev.prefix) {
            Prefix.BULLET -> "${text[prev.indentEnd]} "
            Prefix.TASK -> "${text[prev.indentEnd]} [ ] "
            Prefix.QUOTE -> "> "
            else -> {
                val number = text.substring(prev.indentEnd, prev.markerEnd - 2).toInt()
                "${number + 1}${text[prev.markerEnd - 2]} "
            }
        }
        val prefix = text.substring(prev.start, prev.indentEnd) + marker
        val p = cursor + prefix.length
        return MdEdit(text.substring(0, cursor) + prefix + text.substring(cursor), p, p)
    }

    /** Den URL, markøren eller markeringen står i (også lige før eller efter den), eller null. www. får https://. */
    fun urlAt(text: String, selStart: Int, selEnd: Int): String? {
        val lineStart = text.lastIndexOf('\n', selStart - 1) + 1
        var lineEnd = text.indexOf('\n', selStart)
        if (lineEnd < 0) lineEnd = text.length
        val hit = urls(text, lineStart, lineEnd).firstOrNull { it[0] <= selStart && selEnd <= it[1] } ?: return null
        val url = text.substring(hit[0], hit[1])
        return if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
    }
}
