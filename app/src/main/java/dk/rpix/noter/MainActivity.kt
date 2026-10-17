package dk.rpix.noter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dk.rpix.noter.ui.AboutScreen
import dk.rpix.noter.ui.EditorScreen
import dk.rpix.noter.ui.NoteListScreen
import dk.rpix.noter.ui.NoterTheme
import dk.rpix.noter.ui.ScreenStores
import dk.rpix.noter.data.FileImport
import dk.rpix.noter.ui.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Skærme: liste -> editor, og indstillinger -> om. Navigationen er en simpel streng,
 * der overlever rotation: "list", "settings", "about", "new:<n>" eller "note:<uid>".
 */
class MainActivity : ComponentActivity() {
    private val screens: ScreenStores by viewModels()

    /** Note oprettet fra en fil eller delt tekst, som skal åbnes. */
    private val imported = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            NoterTheme {
                var route by rememberSaveable { mutableStateOf("list") }
                fun go(to: String) {
                    val from = route
                    route = to
                    // Indstillingerne bevares under "om", så felter, der ikke er gemt, ikke går tabt
                    if (from != to && !(from == "settings" && to == "about")) screens.leave(from)
                    if (from == "about" && to == "list") screens.leave("settings")
                }
                BackHandler(enabled = route != "list") { go(if (route == "about") "settings" else "list") }
                val open by imported.collectAsState()
                LaunchedEffect(open) {
                    open?.let {
                        go("note:$it")
                        imported.value = null
                    }
                }
                val owner = remember(route) { screens.owner(route) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    when {
                        route == "settings" -> SettingsScreen(onBack = { go("list") }, onAbout = { go("about") })
                        route == "about" -> AboutScreen(onBack = { go("settings") })
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** "Åbn med" (ACTION_VIEW) eller "Del" (ACTION_SEND): opret en ny note og åbn den. */
    private fun handle(intent: Intent?) {
        val app = application as NoterApp
        val uri: Uri?
        var text: String? = null
        when (intent?.action) {
            Intent.ACTION_VIEW -> uri = intent.data
            Intent.ACTION_SEND -> {
                uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                text = intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            else -> return
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        lifecycleScope.launch {
            val note = when {
                uri != null -> FileImport.fromUri(app, uri)
                text != null -> FileImport.fromText(app, subject, text)
                else -> null
            }
            if (note != null) imported.value = note.uid
            else Toast.makeText(this@MainActivity, R.string.file_open_failed, Toast.LENGTH_LONG).show()
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
