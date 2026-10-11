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
