package dk.rpix.noter.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Objektet er ikke en gyldig note (ingen VJOURNAL, ingen UID eller en ugyldig linje). */
class IcalException(message: String) : Exception(message)

data class ParsedNote(
    val uid: String,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val modified: String = "",
)

/** Én logisk linje: 'NAME;PARAM=x:værdi' -> (NAME, 'PARAM=x', 'værdi'). */
data class ContentLine(val name: String, val params: String, val value: String)

/**
 * iCalendar-laget (kun VJOURNAL). Port af afsnit 1 i reference/notesync.py.
 * Læser og skriver uden at miste egenskaber, som andre klienter har sat.
 */
object Ical {
    const val PRODID = "-//DavNotes Android//EN"
    const val CRLF = "\r\n"

    private val UNESC = Regex("""\\(.)""", RegexOption.DOT_MATCHES_ALL)

    private fun normalizeNewlines(s: String) = s.replace("\r\n", "\n").replace("\r", "\n")

    /** Fjern line folding (RFC 5545 §3.1) og returnér logiske linjer. */
    fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        for (line in normalizeNewlines(text).split("\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.size - 1] = lines.last() + line.substring(1)
            } else if (line != "") {
                lines.add(line)
            }
        }
        return lines
    }

    /** Fold til max 75 oktetter pr. linje uden at skære et UTF-8-tegn over. */
    fun fold(line: String): String {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var bytes = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val n = utf8Len(cp)
            if (bytes + n > limit) {
                parts.add(cur.toString())
                cur.setLength(0)
                bytes = 0
                limit = 74 // fortsættelseslinjer starter med et mellemrum
            }
            cur.appendCodePoint(cp)
            bytes += n
            i += Character.charCount(cp)
        }
        parts.add(cur.toString())
        return parts.joinToString("$CRLF ")
    }

    private fun utf8Len(cp: Int) = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    fun escapeText(s: String): String =
        normalizeNewlines(s)
            .replace("\\", "\\\\").replace(";", "\\;")
            .replace(",", "\\,").replace("\n", "\\n")

    fun unescapeText(s: String): String =
        UNESC.replace(s) { m ->
            val c = m.groupValues[1]
            if (c == "n" || c == "N") "\n" else c
        }

    /** Kolon i citerede parametre ignoreres. */
    fun splitLine(line: String): ContentLine {
        var inQuotes = false
        for ((i, ch) in line.withIndex()) {
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (ch == ':' && !inQuotes) {
                val head = line.substring(0, i)
                val semi = head.indexOf(';')
                val name = if (semi < 0) head else head.substring(0, semi)
                val params = if (semi < 0) "" else head.substring(semi + 1)
                return ContentLine(name.uppercase(), params, line.substring(i + 1))
            }
        }
        throw IcalException("ugyldig iCalendar-linje: $line")
    }

    /** Del en kommasepareret TEXT-liste (CATEGORIES) og respekter \, */
    fun splitCommas(value: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                cur.append(value, i, i + 2)
                i += 2
                continue
            }
            if (c == ',') {
                out.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out.filter { it != "" }.map(::unescapeText)
    }

    private fun vjournalSpan(lines: List<String>): Pair<Int, Int> {
        val s = lines.indexOfFirst { it.uppercase() == "BEGIN:VJOURNAL" }
        val e = if (s < 0) -1 else (s + 1 until lines.size).firstOrNull {
            lines[it].uppercase() == "END:VJOURNAL"
        } ?: -1
        if (s < 0 || e < 0) throw IcalException("objektet indeholder ingen VJOURNAL")
        return s to e
    }

    fun parseIcs(raw: String): ParsedNote {
        val lines = unfold(raw)
        val (s, e) = vjournalSpan(lines)
        val props = mutableMapOf<String, String>()
        val tags = mutableListOf<String>()
        for (l in lines.subList(s + 1, e)) {
            val p = splitLine(l)
            if (p.name == "CATEGORIES") tags += splitCommas(p.value)
            else if (p.name !in props) props[p.name] = p.value
        }
        val uid = props["UID"]
        if (uid.isNullOrEmpty()) throw IcalException("VJOURNAL mangler UID")
        return ParsedNote(
            uid = unescapeText(uid),
            title = unescapeText(props["SUMMARY"] ?: ""),
            body = unescapeText(props["DESCRIPTION"] ?: ""),
            tags = tags,
            modified = props["LAST-MODIFIED"] ?: "",
        )
    }

    /** java.time findes først fra API 26 (minSdk er 24), derfor SimpleDateFormat. */
    fun nowUtc(): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    fun serialize(lines: List<String>): String = lines.joinToString(CRLF) { fold(it) } + CRLF

    private fun tagsLine(tags: List<String>) = "CATEGORIES:" + tags.joinToString(",") { escapeText(it) }

    fun newIcs(uid: String, title: String, body: String = "", tags: List<String> = emptyList()): String {
        val t = nowUtc()
        val lines = mutableListOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:$PRODID", "BEGIN:VJOURNAL",
            "UID:$uid", "DTSTAMP:$t", "CREATED:$t", "LAST-MODIFIED:$t",
            "SUMMARY:" + escapeText(title),
        )
        if (body.isNotEmpty()) lines.add("DESCRIPTION:" + escapeText(body))
        if (tags.isNotEmpty()) lines.add(tagsLine(tags))
        lines += listOf("END:VJOURNAL", "END:VCALENDAR")
        return serialize(lines)
    }

    /**
     * Ret kun de felter, der faktisk ændres (null = urørt). Alt andet (X-egenskaber, CLASS,
     * STATUS, VTIMEZONE, ...) bevares, så vi ikke ødelægger data fra jtx Board o.l.
     * Er intet ændret, returneres [raw] uændret.
     */
    fun updateIcs(raw: String, title: String? = null, body: String? = null, tags: List<String>? = null): String {
        val lines = unfold(raw)
        val (s, e) = vjournalSpan(lines)
        val cur = parseIcs(raw)
        var inner = lines.subList(s + 1, e).toList()
        val added = mutableListOf<String>()

        fun drop(vararg names: String) {
            inner = inner.filter { splitLine(it).name !in names }
        }

        var changed = false
        if (title != null) {
            val t = normalizeNewlines(title)
            if (t != normalizeNewlines(cur.title)) {
                drop("SUMMARY")
                added.add("SUMMARY:" + escapeText(t))
                changed = true
            }
        }
        if (body != null) {
            val b = normalizeNewlines(body)
            if (b != cur.body) {
                drop("DESCRIPTION")
                if (b.isNotEmpty()) added.add("DESCRIPTION:" + escapeText(b))
                changed = true
            }
        }
        if (tags != null && tags != cur.tags) {
            drop("CATEGORIES")
            if (tags.isNotEmpty()) added.add(tagsLine(tags))
            changed = true
        }
        if (!changed) return raw
        val t = nowUtc()
        drop("DTSTAMP", "LAST-MODIFIED")
        added += listOf("DTSTAMP:$t", "LAST-MODIFIED:$t")
        return serialize(lines.subList(0, s + 1) + inner + added + lines.subList(e, lines.size))
    }
}
