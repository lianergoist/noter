package dk.rpix.noter.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFFFFFBF5),
    surface = Color(0xFFFFFBF5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF3BE4D),
    onPrimary = Color(0xFF402D00),
    primaryContainer = Color(0xFF5C4200),
)

/**
 * Noteindhold sættes i serif som i webklienten (style.css); værktøjer og metadata i systemets sans.
 * Størrelser og linjeafstand følger webklientens mobiludgave (titel 1.5rem/1.2, tekst 1.125rem/1.7).
 */
object NoteType {
    val appTitle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em)
    val title = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.01).em)
    val body = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 30.sp)
    val listTitle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp)
    val untitled = listTitle.copy(fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic)
    val snippet = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 21.sp)
}

/** Lys/mørk følger systemet; dynamiske farver fra Android 12. */
@Composable
fun NoterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
