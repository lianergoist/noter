package dk.rpix.noter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dk.rpix.noter.BuildConfig
import dk.rpix.noter.R

/** Et bibliotek i app-bygget og dets ophav. Navnene er egennavne og oversættes ikke. */
private data class Library(val name: String, val owner: String)

// Holdes ens med THIRD_PARTY_NOTICES.md
private val apacheLibraries = listOf(
    Library("AndroidX (Activity, Compose, Core, DataStore, Lifecycle, Room, SQLite, WorkManager m.fl.)", "The Android Open Source Project"),
    Library("Material 3 til Compose", "The Android Open Source Project"),
    Library("Guava ListenableFuture", "The Guava Authors"),
    Library("OkHttp", "Square, Inc."),
    Library("Okio", "Square, Inc."),
    Library("Kotlin Standard Library", "JetBrains s.r.o. and Kotlin Programming Language contributors"),
    Library("kotlinx.coroutines", "JetBrains s.r.o. and contributors"),
    Library("kotlinx.serialization", "JetBrains s.r.o. and contributors"),
    Library("JetBrains Java Annotations", "JetBrains s.r.o."),
    Library("JSpecify", "The JSpecify Authors"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.about)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_author), style = MaterialTheme.typography.bodyMedium)
            LicenseText(stringResource(R.string.about_license_mit), R.raw.license_mit)

            HorizontalDivider()
            Text(stringResource(R.string.about_libraries), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_apache_intro), style = MaterialTheme.typography.bodyMedium)
            for (lib in apacheLibraries) {
                Column {
                    Text(lib.name, style = MaterialTheme.typography.bodyLarge)
                    Text(lib.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LicenseText(stringResource(R.string.about_license_apache), R.raw.license_apache_2_0)

            HorizontalDivider()
            Text(stringResource(R.string.about_psl), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_thanks), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Knap, der folder en licenstekst fra res/raw ud og ind. */
@Composable
private fun LicenseText(label: String, raw: Int) {
    val context = LocalContext.current
    var open by rememberSaveable(raw) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(label) }
    if (open) {
        val text = remember(raw) { context.resources.openRawResource(raw).bufferedReader().use { it.readText() } }
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
