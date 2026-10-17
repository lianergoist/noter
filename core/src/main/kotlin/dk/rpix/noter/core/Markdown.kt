package dk.rpix.noter.core

/** Slags formatering. Se afsnit 6 i docs/SPEC.md. */
enum class MdKind { HEADING, BULLET, NUMBERED, INDENT, BOLD, ITALIC, MARKER, LINK }

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

    private enum class Prefix { NONE, HEADING, BULLET, NUMBERED }

    /** Én linje: [indentEnd] er første ikke-blanke tegn, [markerEnd] hvor indholdet efter præfikset starter. */
    private class Line(
        val start: Int, val end: Int, val indentEnd: Int, val level: Int,
        val prefix: Prefix, val markerEnd: Int, val headingLevel: Int = 0,
    )

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
        if (i + 1 < end && text[i] in "-*+" && text[i + 1] == ' ') {
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
                Prefix.NONE -> if (l.level >= 1 && l.indentEnd < l.end) out += MdSpan(MdKind.INDENT, l.start, l.end, l.level)
            }
            if (l.prefix != Prefix.NONE) markers += intArrayOf(l.indentEnd, l.markerEnd)
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

    /** En række ens '*' eller '_'. Som opener bruges tegn fra højre ende, som closer fra venstre. */
    private class Run(val ch: Char, var start: Int, var len: Int, val orig: Int, val open: Boolean, val close: Boolean)

    /** Fed og kursiv i [from, to) efter en forenklet udgave af CommonMarks delimiter-algoritme. */
    private fun inline(text: String, from: Int, to: Int, out: MutableList<MdSpan>, markers: MutableList<IntArray>) {
        val runs = mutableListOf<Run>()
        // URL'er er uigennemsigtige: ingen fed, kursiv eller escape inden i dem
        val links = urls(text, from, to)
        for (l in links) out += MdSpan(MdKind.LINK, l[0], l[1])
        var li = 0
        var p = from
        while (p < to) {
            if (li < links.size && p >= links[li][0]) {
                p = maxOf(p, links[li][1])
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

    private fun starsBefore(text: String, p: Int): Int {
        var i = p
        while (i > 0 && text[i - 1] == '*') i--
        return p - i
    }

    private fun starsAfter(text: String, p: Int): Int {
        var i = p
        while (i < text.length && text[i] == '*') i++
        return i - p
    }

    /** Er et '*'-løb af længden [run] en markering for fed ([n] = 2) eller kursiv ([n] = 1)? */
    private fun present(run: Int, n: Int) = if (n == 2) run >= 2 else run == 1 || run == 3

    private fun toggleInline(text: String, s: Int, e: Int, n: Int): MdEdit {
        val mark = "*".repeat(n)
        if (s == e) {
            if (present(minOf(starsBefore(text, s), starsAfter(text, s)), n)) {
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
            val l = starsAfter(text, a)
            val r = starsBefore(text, b)
            when {
                l + r < b - a && present(minOf(l, r), n) -> 1
                present(minOf(starsBefore(text, a), starsAfter(text, b)), n) -> 2
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

    fun toggleBold(text: String, selStart: Int, selEnd: Int): MdEdit = toggleInline(text, selStart, selEnd, 2)

    fun toggleItalic(text: String, selStart: Int, selEnd: Int): MdEdit = toggleInline(text, selStart, selEnd, 1)

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
     * Enter i en liste. [cursor] står lige efter det linjeskift, brugeren har indsat. Var linjen før et listepunkt,
     * får den nye linje samme indrykning og markering (næste nummer). Var punktet tomt, og er den nye linje også tom,
     * fjernes punktet i stedet, og listen slutter. Ellers null (intet at gøre).
     */
    fun continueList(text: String, cursor: Int): MdEdit? {
        if (cursor == 0 || text[cursor - 1] != '\n') return null
        val prev = lineAt(text, text.lastIndexOf('\n', cursor - 2) + 1)
        if (prev.prefix != Prefix.BULLET && prev.prefix != Prefix.NUMBERED) return null
        var nextEnd = text.indexOf('\n', cursor)
        if (nextEnd < 0) nextEnd = text.length
        if (text.substring(prev.markerEnd, prev.end).isBlank() && text.substring(cursor, nextEnd).isBlank()) {
            return MdEdit(text.removeRange(prev.start, cursor), prev.start, prev.start)
        }
        val marker = if (prev.prefix == Prefix.BULLET) {
            "${text[prev.indentEnd]} "
        } else {
            val number = text.substring(prev.indentEnd, prev.markerEnd - 2).toInt()
            "${number + 1}${text[prev.markerEnd - 2]} "
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
