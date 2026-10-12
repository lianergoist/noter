package dk.rpix.noter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dk.rpix.noter.NoterApp
import dk.rpix.noter.R
import dk.rpix.noter.core.AuthError
import dk.rpix.noter.core.CollectionInfo
import dk.rpix.noter.core.DavClient
import dk.rpix.noter.core.DavError
import dk.rpix.noter.core.Hrefs
import dk.rpix.noter.core.NetworkError
import dk.rpix.noter.data.Account
import dk.rpix.noter.sync.SyncScheduler
import dk.rpix.noter.sync.davClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Meddelelse på indstillingsskærmen: strengressource + evt. tal (HTTP-status). */
data class Notice(val res: Int, val arg: Int? = null, val error: Boolean = true)

class SettingsViewModel(private val app: NoterApp) : ViewModel() {
    var url by mutableStateOf("")
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    var hasPassword by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Notice?>(null)
        private set
    var collections by mutableStateOf<List<CollectionInfo>?>(null)
        private set

    init {
        viewModelScope.launch {
            url = app.settings.url.first()
            user = app.settings.user.first()
            hasPassword = app.settings.hasPassword.first()
        }
    }

    /** Kontoen fra felterne, eller en fejl at vise. */
    private suspend fun account(): Account? {
        val u = url.trim()
        val parsed = u.toHttpUrlOrNull()
        if (parsed == null) return fail(R.string.err_url_invalid)
        if (!parsed.isHttps && !dk.rpix.noter.sync.allowInsecure(u)) return fail(R.string.err_url_https)
        if (user.isBlank()) return fail(R.string.err_user_missing)
        val pw = password.ifEmpty { if (hasPassword) app.settings.storedPassword() else null }
        if (pw.isNullOrEmpty()) return fail(R.string.err_password_missing)
        return Account(Hrefs.withSlash(u), user.trim(), pw)
    }

    private fun fail(res: Int): Account? {
        notice = Notice(res)
        return null
    }

    private fun run(block: suspend (DavClient, Account) -> Unit) {
        if (busy) return
        viewModelScope.launch {
            notice = null
            val account = account() ?: return@launch
            busy = true
            try {
                block(davClient(account), account)
            } catch (_: AuthError) {
                notice = Notice(R.string.err_login)
            } catch (_: NetworkError) {
                notice = Notice(R.string.err_network)
            } catch (e: DavError) {
                notice = if (e.status == 404) Notice(R.string.err_not_found) else Notice(R.string.err_server, e.status)
            } catch (_: IllegalArgumentException) {
                notice = Notice(R.string.err_url_invalid)
            } finally {
                busy = false
            }
        }
    }

    /** Tjek login og samling, gem kontoen og start synkroniseringen. */
    fun save() = run { client, account ->
        val exists = withContext(Dispatchers.IO) { client.collectionExists() }
        if (!exists) {
            notice = Notice(R.string.err_not_found)
            return@run
        }
        commit(account)
    }

    /** MKCALENDAR med kun VJOURNAL, derefter som [save]. */
    fun createCollection() = run { client, account ->
        withContext(Dispatchers.IO) { client.mkcalendar(app.getString(R.string.notes)) }
        commit(account)
        notice = Notice(R.string.collection_created, error = false)
    }

    fun findCollections() = run { client, _ ->
        val found = withContext(Dispatchers.IO) { client.discover() }
        collections = found
        if (found.isEmpty()) notice = Notice(R.string.no_collections)
    }

    fun choose(c: CollectionInfo) {
        val base = url.trim().toHttpUrlOrNull() ?: return
        url = base.newBuilder().encodedPath(Hrefs.encodePath(c.path)).build().toString()
        collections = null
    }

    private suspend fun commit(account: Account) {
        val oldUrl = app.settings.url.first()
        val oldUser = app.settings.user.first()
        if (oldUrl.isNotEmpty() && (Hrefs.withSlash(oldUrl) != account.url || oldUser != account.user)) {
            // Ny samling: glem den gamle (lokale ændringer beholdes og sendes til den nye)
            withContext(Dispatchers.IO) { app.store.resetForNewCollection() }
            app.settings.clearSyncHistory()
        }
        app.settings.saveAccount(account.url, account.user, password.ifEmpty { null })
        url = account.url
        password = ""
        hasPassword = true
        notice = Notice(R.string.settings_saved, error = false)
        SyncScheduler.schedulePeriodic(app)
        app.appScope.launch { app.syncManager.syncNow() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    val app = noterApp(LocalContext.current)
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(app) } })

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.settings)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = vm.url,
                onValueChange = { vm.url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = { Text(stringResource(R.string.server_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = vm.user,
                onValueChange = { vm.user = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.user)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = vm.password,
                onValueChange = { vm.password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.password)) },
                supportingText = if (vm.hasPassword) ({ Text(stringResource(R.string.password_kept)) }) else null,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.notice?.let { n ->
                Text(
                    if (n.arg != null) stringResource(n.res, n.arg) else stringResource(n.res),
                    color = if (n.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::save, enabled = !vm.busy) { Text(stringResource(R.string.save)) }
                OutlinedButton(onClick = vm::findCollections, enabled = !vm.busy) {
                    Text(stringResource(R.string.find_collections))
                }
            }
            vm.collections?.takeIf { it.isNotEmpty() }?.let { cols ->
                Text(stringResource(R.string.collections_found), style = MaterialTheme.typography.titleSmall)
                for (c in cols) {
                    Column(Modifier.fillMaxWidth().clickable { vm.choose(c) }.padding(vertical = 8.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        Text(c.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedButton(onClick = vm::createCollection, enabled = !vm.busy) {
                Text(stringResource(R.string.create_collection))
            }
            Text(
                stringResource(R.string.sync_interval_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAbout) { Text(stringResource(R.string.about)) }
        }
    }
}
