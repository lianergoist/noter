package dk.rpix.noter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dk.rpix.noter.ui.EditorScreen
import dk.rpix.noter.ui.NoteListScreen
import dk.rpix.noter.ui.NoterTheme
import dk.rpix.noter.ui.ScreenStores
import dk.rpix.noter.ui.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tre skærme: liste -> editor, og indstillinger. Navigationen er en simpel streng,
 * der overlever rotation: "list", "settings", "new:<n>" eller "note:<uid>".
 */
class MainActivity : ComponentActivity() {
    private val screens: ScreenStores by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoterTheme {
                var route by rememberSaveable { mutableStateOf("list") }
                fun go(to: String) {
                    val from = route
                    route = to
                    if (from != to) screens.leave(from)
                }
                BackHandler(enabled = route != "list") { go("list") }
                val owner = remember(route) { screens.owner(route) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    when {
                        route == "settings" -> SettingsScreen(onBack = { go("list") })
                        route.startsWith("new:") || route.startsWith("note:") -> EditorScreen(
                            uid = if (route.startsWith("note:")) route.removePrefix("note:") else null,
                            onBack = { go("list") },
                        )
                        else -> NoteListScreen(
                            onOpen = { uid -> go("note:$uid") },
                            onNew = { go("new:${System.currentTimeMillis()}") },
                            onSettings = { go("settings") },
                        )
                    }
                }
            }
        }
    }

    /** Sync, når appen kommer i forgrunden. */
    override fun onStart() {
        super.onStart()
        val app = application as NoterApp
        lifecycleScope.launch {
            if (app.settings.account() != null && !app.settings.authFailed.first()) app.syncManager.syncNow()
        }
    }
}
