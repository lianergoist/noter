package dk.rpix.noter.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Åbn og gem noter som tekst- og Markdown-filer. Se afsnit 7 i docs/SPEC.md. */
object NoteFile {
    /** Største fil, der åbnes (1 MB). */
    const val MAX_BYTES = 1_000_000

    data class Content(val title: String, val body: String)

    private val HEADING = Regex("""#[ \t]+(.*\S)\s*""")
    private val ILLEGAL = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

    /**
     * Bytes til tekst: UTF-8 (BOM fjernes), UTF-16 med BOM, ellers windows-1252.
     * null, hvis det ikke er tekst (indeholder nul-bytes).
     */
    fun decode(bytes: ByteArray): String? {
        fun startsWith(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        if (startsWith(0xFF, 0xFE)) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (startsWith(0xFE, 0xFF)) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        if (bytes.any { it == 0.toByte() }) return null
        val skip = if (startsWith(0xEF, 0xBB, 0xBF)) 3 else 0
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
    }

    /**
     * Titel og tekst af en fil. Er første linje en overskrift på niveau 1 ("# Titel"), bliver den titlen;
     * ellers bruges filnavnet uden endelse. Linjeskift normaliseres, og tomme linjer til sidst fjernes.
     */
    fun parse(fileName: String?, text: String): Content {
        val norm = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val firstEnd = norm.indexOf('\n').let { if (it < 0) norm.length else it }
        val heading = HEADING.matchEntire(norm.substring(0, firstEnd))
        if (heading != null) {
            val rest = norm.substring(minOf(firstEnd + 1, norm.length)).lines().dropWhile { it.isBlank() }
            return Content(heading.groupValues[1].trim(), rest.joinToString("\n").trimEnd('\n'))
        }
        val name = fileName.orEmpty()
        val title = if (name.lastIndexOf('.') > 0) name.substringBeforeLast('.') else name
        return Content(title, norm.trimEnd('\n'))
    }

    /** Markdown-fil: "# Titel", tom linje, teksten. Kan læses igen med [parse]. */
    fun toMarkdown(title: String, body: String): String {
        val head = if (title.isBlank()) "" else "# ${title.trim()}\n"
        return when {
            body.isEmpty() -> head
            head.isEmpty() -> "$body\n"
            else -> "$head\n$body\n"
        }
    }

    /** Tekstfil: kun teksten; titlen bliver filnavnet. */
    fun toText(body: String): String = if (body.isEmpty()) "" else "$body\n"

    /** Forslag til filnavn: titlen uden tegn, som filsystemer afviser, højst 80 tegn. */
    fun fileName(title: String, extension: String, fallback: String): String {
        var name = ILLEGAL.replace(title, " ").replace(Regex("""\s+"""), " ").trim().trimStart('.').trim()
        if (name.length > 80) {
            name = name.take(80)
            if (name.last().isHighSurrogate()) name = name.dropLast(1)
            name = name.trimEnd()
        }
        return "${name.ifEmpty { fallback }}.$extension"
    }
}
