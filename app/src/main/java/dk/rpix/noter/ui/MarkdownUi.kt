package dk.rpix.noter.ui

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dk.rpix.noter.R
import dk.rpix.noter.core.MdEdit
import dk.rpix.noter.core.MdLink
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
                MdKind.LINK -> SpanStyle(color = link, textDecoration = TextDecoration.Underline, fontSize = 0.9.em)
                MdKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, fontSize = 0.9.em)
                MdKind.DONE -> SpanStyle(color = marker, textDecoration = TextDecoration.LineThrough)
                MdKind.QUOTE -> {
                    var i = sp.start
                    while (chars[i] == ' ' || chars[i] == '\t') i++
                    invisible += i
                    SpanStyle(color = quote)
                }
                MdKind.CHECKBOX -> {
                    // "- [ ]" vises som "☐" på stregens plads; resten bliver nulbredde
                    val dash = sp.start - 2
                    chars[dash] = if (sp.level == 1) '☑' else '☐'
                    for (i in dash + 1 until sp.end) chars[i] = '\u200B'
                    styles += AnnotatedString.Range(SpanStyle(color = if (sp.level == 1) checked else bullet), dash, dash + 1)
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
        return TransformedText(AnnotatedString(String(chars), styles), OffsetMapping.Identity)
    }

    private fun headingSize(level: Int) = when (level) {
        1 -> 1.4.em
        2 -> 1.2.em
        else -> 1.1.em
    }
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
    val quotes = remember(value.text) { Markdown.spans(value.text).filter { it.kind == MdKind.QUOTE } }
    val links by rememberUpdatedState(remember(value.text) { Markdown.links(value.text) })
    val active by rememberUpdatedState(transformation.active)
    val onLink by rememberUpdatedState(onLinkClick)

    // Linkteksten under et tryk, hvis linket er foldet sammen
    fun linkAt(pos: Offset): MdLink? {
        val l = layout ?: return null
        if (l.layoutInput.text.length != value.text.length) return null
        val o = l.getOffsetForPosition(pos)
        return links.firstOrNull { link ->
            val text = link.start + 1 until link.start + 1 + link.text.length
            link.start !in active && listOf(o - 1, o).any { it in text && l.getBoundingBox(it).contains(pos) }
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle.merge(TextStyle(color = MaterialTheme.colorScheme.onSurface)),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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
                                    val link = linkAt(down.position) ?: return@awaitEachGesture
                                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        waitForUpOrCancellation(PointerEventPass.Initial)
                                    } ?: return@awaitEachGesture
                                    if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                                    up.consume()
                                    onLink(link)
                                }
                            }
                            .drawBehind {
                            val l = layout ?: return@drawBehind
                            if (l.layoutInput.text.length != value.text.length) return@drawBehind
                            val w = 5.dp.toPx()
                            for (q in quotes) {
                                var i = q.start
                                while (value.text[i] == ' ' || value.text[i] == '\t') i++
                                val box = l.getBoundingBox(i)
                                val top = l.getLineTop(l.getLineForOffset(q.start))
                                val bottom = l.getLineBottom(l.getLineForOffset(q.end))
                                drawRect(quoteBar, Offset(box.left + (box.width - w) / 2, top), Size(w, bottom - top))
                            }
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
