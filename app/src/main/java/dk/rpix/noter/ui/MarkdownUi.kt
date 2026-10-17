package dk.rpix.noter.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import dk.rpix.noter.core.MdKind
import dk.rpix.noter.core.Markdown

/**
 * Viser Markdown i editoren uden at ændre teksten: markeringerne bliver stående, men dæmpes,
 * og punkttegn vises som •. Teksten har samme længde før og efter, så offset-mappingen er identitet.
 */
data class MarkdownTransformation(val marker: Color, val bullet: Color, val link: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val spans = Markdown.spans(s)
        val chars = s.toCharArray()
        val styles = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        // Markeringer først, så punkttegnets farve vinder over den dæmpede
        for (sp in spans.filter { it.kind == MdKind.MARKER }) {
            styles += AnnotatedString.Range(SpanStyle(color = marker), sp.start, sp.end)
        }
        for (sp in spans) {
            val style = when (sp.kind) {
                MdKind.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(sp.level))
                MdKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                MdKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                MdKind.LINK -> SpanStyle(color = link, textDecoration = TextDecoration.Underline)
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
        return TransformedText(AnnotatedString(String(chars), styles), OffsetMapping.Identity)
    }

    private fun headingSize(level: Int) = when (level) {
        1 -> 1.4.em
        2 -> 1.2.em
        else -> 1.1.em
    }
}

private class FormatAction(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
    val op: (String, Int, Int) -> MdEdit,
)

private val actions = listOf(
    FormatAction(R.drawable.ic_format_bold, R.string.format_bold, Markdown::toggleBold),
    FormatAction(R.drawable.ic_format_italic, R.string.format_italic, Markdown::toggleItalic),
    FormatAction(R.drawable.ic_format_heading, R.string.format_heading, Markdown::cycleHeading),
    FormatAction(R.drawable.ic_format_list_bulleted, R.string.format_bullets, Markdown::toggleBullets),
    FormatAction(R.drawable.ic_format_list_numbered, R.string.format_numbers, Markdown::toggleNumbers),
    FormatAction(R.drawable.ic_format_indent_decrease, R.string.format_outdent, Markdown::outdent),
    FormatAction(R.drawable.ic_format_indent_increase, R.string.format_indent, Markdown::indent),
)

/**
 * Værktøjslinjen over tastaturet. Formateringsknapperne kan rulles; "Åbn link" står fast yderst til højre,
 * når markøren står i en URL ([url]).
 */
@Composable
fun FormatBar(url: String?, onOpenUrl: (String) -> Unit, onFormat: ((String, Int, Int) -> MdEdit) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                for (a in actions) {
                    IconButton(onClick = { onFormat(a.op) }) {
                        Icon(painterResource(a.icon), contentDescription = stringResource(a.label))
                    }
                }
            }
            if (url != null) {
                IconButton(onClick = { onOpenUrl(url) }) {
                    Icon(
                        painterResource(R.drawable.ic_open_in_new),
                        contentDescription = stringResource(R.string.open_link),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
