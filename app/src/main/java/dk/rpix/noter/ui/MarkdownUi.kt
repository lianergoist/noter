package dk.rpix.noter.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dk.rpix.noter.R
import dk.rpix.noter.core.MdEdit
import dk.rpix.noter.core.MdLink
import dk.rpix.noter.core.MdSpan
import dk.rpix.noter.core.MdKind
import dk.rpix.noter.core.Markdown

/**
 * Viser Markdown i editoren uden at ændre teksten: markeringerne bliver stående, men dæmpes,
 * punkttegn vises som • og afkrydsningsfelter som ☐/☑. Teksten har samme længde før og efter
 * (overskydende tegn bliver nulbredde-mellemrum), så offset-mappingen er identitet.
 * Citatets '>' gøres usynligt; stregen tegnes af [MarkdownBodyField].
 * Uden for [active] (linjerne under markøren) skjules '#'-præfikset og inline-markeringerne helt
 * ([Markdown.hidden]); de bliver nulbredde-mellemrum, så længden stadig er den samme.
 */
data class MarkdownTransformation(
    val marker: Color,
    val bullet: Color,
    val link: Color,
    val quote: Color,
    val codeBackground: Color,
    val checked: Color,
    val active: IntRange = IntRange.EMPTY,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val spans = Markdown.spans(s)
        val chars = s.toCharArray()
        val styles = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        val invisible = mutableListOf<Int>()
        // Markeringer først, så punkttegnets farve vinder over den dæmpede
        for (sp in spans.filter { it.kind == MdKind.MARKER }) {
            styles += AnnotatedString.Range(SpanStyle(color = marker), sp.start, sp.end)
        }
        for (sp in spans) {
            val style = when (sp.kind) {
                MdKind.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(sp.level))
                MdKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                MdKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                // Bare URL'er lidt mindre; linktekst i [tekst](url) i normal størrelse
                MdKind.LINK -> SpanStyle(
                    color = link,
                    textDecoration = TextDecoration.Underline,
                    fontSize = if (sp.level == 0) 0.9.em else TextUnit.Unspecified,
                )
                // Baggrunden tegnes af [MarkdownBodyField], så den ikke fylder hele linjehøjden
                MdKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = CODE_SIZE.em)
                MdKind.DONE -> SpanStyle(color = marker, textDecoration = TextDecoration.LineThrough)
                MdKind.QUOTE -> {
                    var i = sp.start
                    while (chars[i] == ' ' || chars[i] == '\t') i++
                    invisible += i
                    SpanStyle(color = quote)
                }
                MdKind.CHECKBOX -> {
                    // "- [ ]" bliver en usynlig pladsholder på stregens plads, hvor [MarkdownBodyField] tegner boksen
                    // (et "☐" fra en symbolskrift kan gøre linjen højere); resten bliver nulbredde
                    val dash = sp.start - 2
                    chars[dash] = 'M'
                    for (i in dash + 1 until sp.end) chars[i] = '\u200B'
                    styles += AnnotatedString.Range(SpanStyle(color = Color.Transparent), dash, dash + 1)
                    null
                }
                MdKind.BULLET -> {
                    var i = sp.start
                    while (chars[i] == ' ' || chars[i] == '\t') i++
                    chars[i] = '•'
                    styles += AnnotatedString.Range(SpanStyle(color = bullet), i, i + 1)
                    null
                }
                else -> null
            }
            if (style != null) styles += AnnotatedString.Range(style, sp.start, sp.end)
        }
        // Til sidst, så citatets farve ikke overskriver den gennemsigtige '>'
        for (i in invisible) styles += AnnotatedString.Range(SpanStyle(color = Color.Transparent), i, i + 1)
        for (sp in Markdown.hidden(s)) {
            for (i in sp.start until sp.end) if (i !in active) chars[i] = '\u200B'
        }
        // Linjeafstand: usynlige tegn (mellemrum, linjeskift, nulbredde-tegn og et ekstra tegn efter sidste linje)
        // hæves, så hver linje, også en ombrudt, får luft over sig, uden at bogstaverne flyttes. En fast linjehøjde
        // kan ikke bruges, for den giver alle linjer samme højde. Lister og citater får en halv linje mere over
        // og under sig ([blockStarts]). Mellemrum i links og afkrydsede punkter hæves ikke, da deres
        // under- og gennemstregning ellers ville hoppe.
        val decorated = BooleanArray(s.length)
        for (sp in spans) if (sp.kind == MdKind.LINK || sp.kind == MdKind.DONE) decorated.fill(true, sp.start, sp.end)
        var i = 0
        while (i < s.length) {
            if (chars[i].isLiftable() && !decorated[i] && s[i] != '\n') {
                val start = i
                while (i < s.length && chars[i].isLiftable() && !decorated[i] && s[i] != '\n') i++
                styles += AnnotatedString.Range(LINE_STYLE, start, i)
            } else i++
        }
        val blockStarts = blockStarts(s, spans)
        var lineStart = 0
        while (true) {
            val end = s.indexOf('\n', lineStart).let { if (it < 0) s.length else it }
            styles += AnnotatedString.Range(if (lineStart in blockStarts) BLOCK_STYLE else LINE_STYLE, end, end + 1)
            if (end == s.length) break
            lineStart = end + 1
        }
        return TransformedText(AnnotatedString(String(chars) + '\u200B', styles), EndMapping(s.length))
    }

    /** Teksten plus ét tegn til sidst: identitet, men intet efter tekstens slutning. */
    private class EndMapping(val length: Int) : OffsetMapping {
        override fun originalToTransformed(offset: Int) = offset
        override fun transformedToOriginal(offset: Int) = minOf(offset, length)
    }

    private fun Char.isLiftable() = this == ' ' || this == '\t' || this == '\u200B'

    /**
     * Starten af linjer, der skal have en halv linje ekstra over sig: første linje i en liste eller et citat og
     * første linje efter en, når linjen før er ikke-tom og af en anden slags (punkter, numre og afkrydsning er én slags).
     */
    fun blockStarts(s: String, spans: List<MdSpan>): Set<Int> {
        val kinds = HashMap<Int, MdKind>()
        for (sp in spans) when (sp.kind) {
            MdKind.BULLET, MdKind.NUMBERED, MdKind.TASK -> kinds[sp.start] = MdKind.BULLET
            MdKind.QUOTE -> kinds[sp.start] = MdKind.QUOTE
            else -> {}
        }
        val result = HashSet<Int>()
        var prev = 0
        var start = s.indexOf('\n') + 1
        while (start > 0) {
            val end = s.indexOf('\n', start).let { if (it < 0) s.length else it }
            if (kinds[start] != kinds[prev] && s.substring(prev, start - 1).isNotBlank() && s.substring(start, end).isNotBlank()) {
                result += start
            }
            prev = start
            start = s.indexOf('\n', start) + 1
        }
        return result
    }

    companion object {
        const val CODE_SIZE = 0.9f
        // Hævning af linjeslut i forhold til skriftens ascent: ca. 30 sp pr. linje ved 18 sp, en halv linje mere før en blok
        const val LINE_LIFT = 0.55f
        const val BLOCK_LIFT = 1.45f
        private val LINE_STYLE = SpanStyle(baselineShift = BaselineShift(LINE_LIFT))
        private val BLOCK_STYLE = SpanStyle(baselineShift = BaselineShift(BLOCK_LIFT))

        fun headingScale(level: Int) = when (level) {
            1 -> 1.4f
            2 -> 1.2f
            else -> 1.1f
        }
    }

    private fun headingSize(level: Int) = headingScale(level).em

}

/**
 * Tegnene på de linjer, markeringen i [value] berører (til [MarkdownTransformation.active]).
 * Uden fokus er ingen linje aktiv, så alle markeringer skjules.
 */
fun activeLines(value: TextFieldValue, focused: Boolean): IntRange {
    if (!focused) return IntRange.EMPTY
    val text = value.text
    val start = text.lastIndexOf('\n', value.selection.min - 1) + 1
    val end = text.indexOf('\n', value.selection.max).let { if (it < 0) text.length else it }
    return start..end
}

private class FormatAction(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
    val op: (String, Int, Int) -> MdEdit,
)

private val actions = listOf(
    FormatAction(R.drawable.ic_text_format, R.string.format_emphasis, Markdown::cycleEmphasis),
    FormatAction(R.drawable.ic_format_heading, R.string.format_heading, Markdown::cycleHeading),
    FormatAction(R.drawable.ic_format_list_bulleted, R.string.format_bullets, Markdown::toggleBullets),
    FormatAction(R.drawable.ic_format_list_numbered, R.string.format_numbers, Markdown::toggleNumbers),
    FormatAction(R.drawable.ic_checklist, R.string.format_task, Markdown::toggleTask),
    FormatAction(R.drawable.ic_format_quote, R.string.format_quote, Markdown::toggleQuote),
    FormatAction(R.drawable.ic_code, R.string.format_code, Markdown::toggleCode),
    FormatAction(R.drawable.ic_link, R.string.format_link, Markdown::insertLink),
    FormatAction(R.drawable.ic_format_indent_decrease, R.string.format_outdent, Markdown::outdent),
    FormatAction(R.drawable.ic_format_indent_increase, R.string.format_indent, Markdown::indent),
)

/**
 * Værktøjslinjen over tastaturet. Formateringsknapperne kan rulles; "Åbn link" står fast yderst til højre,
 * når markøren står i en URL ([url]).
 */
@Composable
fun FormatBar(url: String?, onOpenUrl: (String) -> Unit, onFormat: ((String, Int, Int) -> MdEdit) -> Unit) {
    // Smallere knapper end Material 3's mindste berøringsflade (48 dp), men samme højde, så alle ti kan ses
    val button = Modifier.size(width = 34.dp, height = 48.dp)
    Surface(tonalElevation = 3.dp) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    for (a in actions) {
                        IconButton(onClick = { onFormat(a.op) }, modifier = button) {
                            Icon(painterResource(a.icon), contentDescription = stringResource(a.label), Modifier.size(20.dp))
                        }
                    }
                }
                if (url != null) {
                    IconButton(onClick = { onOpenUrl(url) }, modifier = button) {
                        Icon(
                            painterResource(R.drawable.ic_open_in_new),
                            contentDescription = stringResource(R.string.open_link),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Notens tekstfelt: som Material 3's TextField, men med adgang til tekstlayoutet, så citater kan få
 * en farvet streg i venstre side (på '>'-tegnets plads). Et kort tryk på teksten i et sammenfoldet link
 * (uden for [MarkdownTransformation.active]) kalder [onLinkClick] i stedet for at sætte markøren.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownBodyField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    textStyle: TextStyle,
    transformation: MarkdownTransformation,
    quoteBar: Color,
    colors: TextFieldColors,
    placeholder: @Composable () -> Unit,
    onLinkClick: (MdLink) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val spans = remember(value.text) { Markdown.spans(value.text) }
    val quotes = spans.filter { it.kind == MdKind.QUOTE }
    val codes = spans.filter { it.kind == MdKind.CODE }
    val boxes = spans.filter { it.kind == MdKind.CHECKBOX }
    val headings = spans.filter { it.kind == MdKind.HEADING }
    val current by rememberUpdatedState(value)
    val currentBoxes by rememberUpdatedState(boxes)
    val onChange by rememberUpdatedState(onValueChange)
    val density = LocalDensity.current
    val font = remember(textStyle.fontSize, density) {
        Paint().apply { typeface = Typeface.SERIF; textSize = with(density) { textStyle.fontSize.toPx() } }.fontMetrics
    }
    val surface = MaterialTheme.colorScheme.surface
    val cursorColor = MaterialTheme.colorScheme.primary
    // Markøren tegnes i skriftens højde; Compose' egen går fra linjens top til bund, inkl. luften over linjen
    val focused by interaction.collectIsFocusedAsState()
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(focused, value.selection, value.text) {
        cursorOn = true
        if (!focused || !value.selection.collapsed) return@LaunchedEffect
        while (true) {
            delay(500)
            cursorOn = false
            delay(500)
            cursorOn = true
        }
    }
    val blockStarts = remember(value.text) { transformation.blockStarts(value.text, spans) }
    val links by rememberUpdatedState(remember(value.text) { Markdown.links(value.text) })
    val active by rememberUpdatedState(transformation.active)
    val onLink by rememberUpdatedState(onLinkClick)

    // Linkteksten under et tryk, hvis linket er foldet sammen
    fun linkAt(pos: Offset): MdLink? {
        val l = layout ?: return null
        if (l.layoutInput.text.length != current.text.length + 1) return null
        val o = l.getOffsetForPosition(pos)
        return links.firstOrNull { link ->
            val text = link.start + 1 until link.start + 1 + link.text.length
            link.start !in active && listOf(o - 1, o).any { it in text && l.getBoundingBox(it).contains(pos) }
        }
    }
    // Afkrydsningsfeltet under et tryk ([slop] ekstra plads til fingeren)
    fun boxAt(pos: Offset, slop: Float): MdSpan? {
        val l = layout ?: return null
        if (l.layoutInput.text.length != current.text.length + 1) return null
        val size = with(density) { textStyle.fontSize.toPx() }
        return currentBoxes.firstOrNull { b ->
            val r = boxRect(l, b, size) ?: return@firstOrNull false
            pos.x in r.left - slop..r.right + slop && pos.y in r.top - slop..r.bottom + slop
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        // Linjeafstanden kommer fra [MarkdownTransformation] (en fast linjehøjde ville give alle linjer samme højde)
        textStyle = textStyle.merge(TextStyle(color = MaterialTheme.colorScheme.onSurface)).copy(lineHeight = TextUnit.Unspecified),
        cursorBrush = SolidColor(Color.Transparent),
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        interactionSource = interaction,
        onTextLayout = { layout = it },
        decorationBox = { inner ->
            TextFieldDefaults.DecorationBox(
                value = value.text,
                innerTextField = {
                    Box(
                        Modifier
                            .pointerInput(Unit) {
                                // Opslugt "op" betyder, at tekstfeltet ikke sætter markøren eller får fokus
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val link = linkAt(down.position)
                                    val box = if (link == null) boxAt(down.position, 12.dp.toPx()) else null
                                    if (link == null && box == null) return@awaitEachGesture
                                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        waitForUpOrCancellation(PointerEventPass.Initial)
                                    } ?: return@awaitEachGesture
                                    if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                                    up.consume()
                                    if (link != null) {
                                        onLink(link)
                                    } else if (box != null) {
                                        // Et tryk på boksen krydser af eller fjerner krydset
                                        val v = current
                                        val mark = if (box.level == 1) ' ' else 'x'
                                        val text = v.text.substring(0, box.start + 1) + mark + v.text.substring(box.start + 2)
                                        onChange(v.copy(text = text))
                                    }
                                }
                            }
                            .drawBehind {
                            val l = layout ?: return@drawBehind
                            if (l.layoutInput.text.length != value.text.length + 1) return@drawBehind
                            val w = 5.dp.toPx()
                            // Afstanden før en blok hører ikke med til stregen
                            val ascent = -Paint().apply { typeface = Typeface.SERIF; textSize = textStyle.fontSize.toPx() }.ascent()
                            val gap = ascent * (MarkdownTransformation.BLOCK_LIFT - MarkdownTransformation.LINE_LIFT)
                            for (q in quotes) {
                                var i = q.start
                                while (value.text[i] == ' ' || value.text[i] == '\t') i++
                                val box = l.getBoundingBox(i)
                                val top = l.getLineTop(l.getLineForOffset(q.start)) + if (q.start in blockStarts) gap else 0f
                                val bottom = l.getLineBottom(l.getLineForOffset(q.end))
                                drawRect(quoteBar, Offset(box.left + (box.width - w) / 2, top), Size(w, bottom - top))
                            }
                            // Kode: grå baggrund om skriftens højde (ikke hele linjen), stykke for stykke ved ombrydning
                            val em = textStyle.fontSize.toPx() * MarkdownTransformation.CODE_SIZE
                            val pad = 0.15f * em
                            for (c in codes) {
                                if (c.start >= c.end) continue
                                for (line in l.getLineForOffset(c.start)..l.getLineForOffset(c.end - 1)) {
                                    val a = maxOf(c.start, l.getLineStart(line))
                                    val b = minOf(c.end, l.getLineEnd(line, visibleEnd = true))
                                    if (a >= b) continue
                                    val left = l.getBoundingBox(a).left
                                    val right = l.getBoundingBox(b - 1).right
                                    val base = l.getLineBaseline(line)
                                    drawRoundRect(
                                        transformation.codeBackground,
                                        Offset(minOf(left, right) - pad, base - 1.05f * em),
                                        Size(kotlin.math.abs(right - left) + 2 * pad, 1.4f * em),
                                        CornerRadius(0.25f * em),
                                    )
                                }
                            }
                            // Afkrydsningsfelter: en boks på stregens plads, udfyldt med flueben, når den er afkrydset
                            val size = textStyle.fontSize.toPx()
                            for (b in boxes) {
                                val r = boxRect(l, b, size) ?: continue
                                val corner = CornerRadius(0.12f * size)
                                if (b.level == 1) {
                                    drawRoundRect(transformation.checked, r.topLeft, r.size, corner)
                                    val check = Path().apply {
                                        moveTo(r.left + 0.22f * r.width, r.top + 0.52f * r.height)
                                        lineTo(r.left + 0.42f * r.width, r.top + 0.72f * r.height)
                                        lineTo(r.left + 0.78f * r.width, r.top + 0.3f * r.height)
                                    }
                                    drawPath(check, surface, style = Stroke(0.1f * size, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                } else {
                                    val stroke = 0.08f * size
                                    drawRoundRect(
                                        transformation.bullet,
                                        r.topLeft + Offset(stroke / 2, stroke / 2),
                                        Size(r.width - stroke, r.height - stroke),
                                        corner,
                                        style = Stroke(stroke),
                                    )
                                }
                            }
                        }
                            .drawWithContent {
                                drawContent()
                                val l = layout ?: return@drawWithContent
                                val sel = value.selection
                                if (!focused || !sel.collapsed || !cursorOn || l.layoutInput.text.length != value.text.length + 1) {
                                    return@drawWithContent
                                }
                                val line = l.getLineForOffset(sel.start)
                                val scale = headings.firstOrNull { sel.start in it.start..it.end }
                                    ?.let { MarkdownTransformation.headingScale(it.level) } ?: 1f
                                val base = l.getLineBaseline(line)
                                val top = maxOf(l.getLineTop(line), base + font.ascent * scale)
                                val bottom = minOf(l.getLineBottom(line), base + font.descent * scale)
                                val w = 2.dp.toPx()
                                val x = l.getCursorRect(sel.start).left.coerceAtLeast(w / 2)
                                drawLine(cursorColor, Offset(x, top), Offset(x, bottom), w)
                            },
                    ) { inner() }
                },
                enabled = true,
                singleLine = false,
                visualTransformation = transformation,
                interactionSource = interaction,
                placeholder = placeholder,
                colors = colors,
            )
        },
    )
}

/** Redigér et link: tekst og URL. Gem er kun muligt, når resultatet er et gyldigt link ([Markdown.editLink]). */
@Composable
fun LinkDialog(link: MdLink, onSave: (label: String, url: String?) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(link.text) }
    var url by remember { mutableStateOf(link.url) }
    val valid = Markdown.editLink("", MdLink(0, 0, "", ""), label, url) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.link_text)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.link_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label, url) }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(label, null) }) { Text(stringResource(R.string.link_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Boksen for et afkrydsningsfelt: på pladsholderen, hvor stregen i "- [ ]" står, og på linje med teksten. */
private fun boxRect(l: TextLayoutResult, box: MdSpan, fontSize: Float): Rect? {
    val dash = box.start - 2
    if (dash < 0 || dash >= l.layoutInput.text.length) return null
    val slot = l.getBoundingBox(dash)
    val side = 0.78f * fontSize
    val bottom = l.getLineBaseline(l.getLineForOffset(dash)) + 0.06f * fontSize
    val left = slot.left + (slot.width - side) / 2
    return Rect(left, bottom - side, left + side, bottom)
}
