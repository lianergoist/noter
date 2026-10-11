package dk.rpix.noter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dk.rpix.noter.NoterApp
import dk.rpix.noter.R
import dk.rpix.noter.core.Note
import dk.rpix.noter.core.search
import dk.rpix.noter.data.SyncError
import dk.rpix.noter.data.SyncState
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ListState(val total: Int, val items: List<Note>)

data class StatusState(
    val running: Boolean = false,
    val configured: Boolean = true,
    val sync: SyncState = SyncState(null, null),
)

class NoteListViewModel(private val app: NoterApp) : ViewModel() {
    val query = MutableStateFlow("")

    val notes: StateFlow<ListState?> = combine(app.store.observeVisible(), query) { all, q ->
        ListState(all.size, all.search(q))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val status: StateFlow<StatusState> = combine(
        app.syncManager.running, app.settings.url, app.settings.hasPassword, app.settings.syncState,
    ) { running, url, hasPassword, sync ->
        StatusState(running, url.isNotBlank() && hasPassword, sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusState())

    val conflicts = app.syncManager.conflicts

    fun refresh() {
        viewModelScope.launch { app.syncManager.syncNow() }
    }
}

fun noterApp(context: android.content.Context) = context.applicationContext as NoterApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val app = noterApp(LocalContext.current)
    val vm: NoteListViewModel = viewModel(factory = viewModelFactory { initializer { NoteListViewModel(app) } })
    val list by vm.notes.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ConflictMessages(vm.conflicts, snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notes)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            val newNote = stringResource(R.string.new_note)
            ExtendedFloatingActionButton(
                onClick = onNew,
                // Etiketten når ikke frem til TalkBack af sig selv (knappen var navnløs), så sæt den eksplicit
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = newNote
                    role = Role.Button
                    onClick { onNew(); true }
                },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(newNote) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.search_notes)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.query.value = "" }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
            StatusLine(status, onLogin = onSettings)
            PullToRefreshBox(
                isRefreshing = status.running,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val l = list
                when {
                    l == null -> Unit
                    l.items.isEmpty() -> EmptyText(
                        stringResource(if (l.total == 0) R.string.empty_notes else R.string.empty_search),
                    )
                    else -> NoteList(l.items, onOpen)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(s: StatusState, onLogin: () -> Unit) {
    val error = when {
        !s.configured -> SyncError.NotConfigured
        else -> s.sync.error
    }
    val text = when {
        s.running -> stringResource(R.string.syncing)
        error != null -> syncErrorText(error)
        s.sync.lastSuccess != null -> {
            val t = Date(s.sync.lastSuccess)
            val now = Date()
            val label = if (DateLabels.dayDiff(t, now) <= 0) DateLabels.shortTime(t, now) else DateLabels.fullTime(t)
            stringResource(R.string.sync_last, label)
        }
        else -> stringResource(R.string.sync_never)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null && !s.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!s.running && (error == SyncError.Auth || error == SyncError.NotConfigured)) {
            TextButton(onClick = onLogin) { Text(stringResource(R.string.log_in)) }
        }
    }
}

@Composable
fun syncErrorText(e: SyncError): String = when (e) {
    SyncError.Network -> stringResource(R.string.sync_offline)
    SyncError.Auth -> stringResource(R.string.sync_auth)
    SyncError.NotConfigured -> stringResource(R.string.sync_not_configured)
    is SyncError.Server -> stringResource(R.string.sync_server_error, e.status)
    is SyncError.Other -> stringResource(R.string.sync_other_error, e.message)
}

@Composable
private fun EmptyText(text: String) {
    // LazyColumn, så træk-for-at-opdatere også virker på en tom liste
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                text,
                modifier = Modifier.padding(32.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoteList(items: List<Note>, onOpen: (String) -> Unit) {
    val now = remember(items) { Date() }
    val grouped = remember(items) {
        items.map { it to DateLabels.parseStamp(it.modified) }
            .groupBy { (_, t) -> DateLabels.groupOf(t, now) }
            .entries
            .sortedBy { (g, _) -> if (g == DateGroup.NoDate) 1 else 0 } // "Uden dato" til sidst
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        for ((group, notes) in grouped) {
            item(key = "group:$group") { GroupHeader(group) }
            items(notes, key = { it.first.uid }) { (n, t) -> NoteRow(n, t, now, onOpen) }
        }
    }
}

@Composable
private fun GroupHeader(g: DateGroup) {
    val label = when (g) {
        DateGroup.Today -> stringResource(R.string.group_today)
        DateGroup.Yesterday -> stringResource(R.string.group_yesterday)
        DateGroup.Last7Days -> stringResource(R.string.group_last_7_days)
        DateGroup.NoDate -> stringResource(R.string.group_no_date)
        is DateGroup.Month -> DateLabels.monthLabel(g).replaceFirstChar { it.uppercase() }
    }
    Text(
        label,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun NoteRow(n: Note, t: Date?, now: Date, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onOpen(n.uid) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                n.title.ifEmpty { stringResource(R.string.untitled) },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = if (n.title.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (t != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    DateLabels.shortTime(t, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val first = n.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (first != null) {
            Text(
                first,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val meta = listOfNotNull(
            n.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
            if (n.dirty) stringResource(R.string.unsent) else null,
        )
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
