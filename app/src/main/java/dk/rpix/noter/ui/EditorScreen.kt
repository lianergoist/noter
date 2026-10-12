package dk.rpix.noter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dk.rpix.noter.NoterApp
import dk.rpix.noter.R
import dk.rpix.noter.core.Note
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Det, editoren viser: titel, tekst og tags (efter opdeling på komma). */
data class Form(val title: String, val body: String, val tags: List<String>) {
    fun isBlank() = title.isBlank() && body.isBlank() && tags.isEmpty()

    /** Samme indhold som noten? Linjeskift normaliseres som i iCalendar-laget. */
    fun sameAs(n: Note) = title == n.title && norm(body) == n.body && tags == n.tags

    companion object {
        val EMPTY = Form("", "", emptyList())
        fun of(n: Note) = Form(n.title, n.body, n.tags)
        private fun norm(s: String) = s.replace("\r\n", "\n").replace("\r", "\n")
    }
}

/** Tags splittes på komma; tomme og dubletter fjernes (som i webklienten). */
fun parseTags(s: String): List<String> = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

enum class SaveStatus { NONE, UNSAVED, SAVING, SAVED }

class EditorViewModel(private val app: NoterApp, initialUid: String?) : ViewModel() {
    private val store = app.store

    var title by mutableStateOf("")
        private set
    var tagsText by mutableStateOf("")
        private set
    var body by mutableStateOf("")
        private set
    var status by mutableStateOf(SaveStatus.NONE)
        private set
    var modified by mutableStateOf("")
        private set
    var loaded by mutableStateOf(initialUid == null)
        private set
    var hasNote by mutableStateOf(initialUid != null)
        private set

    /** Beskeder (strengressourcer) til snackbaren. */
    val messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** true = luk editoren (noten findes ikke længere). */
    val closed = MutableStateFlow(false)

    private val uid = MutableStateFlow(initialUid)
    private var baseline = Form.EMPTY // det, den lokale række sidst indeholdt
    private var baseRaw: String? = null // rå tekst, som baseline svarer til
    private var discarded = false
    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    private fun form() = Form(title, body, parseTags(tagsText))
    private fun touched() = form() != baseline

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            uid.filterNotNull().flatMapLatest { store.observe(it) }.collect { onRow(it) }
        }
    }

    /** Rækken er ændret i databasen (af os selv eller af en sync). */
    private suspend fun onRow(n: Note?) = saveMutex.withLock {
        if (!loaded) {
            if (n == null || n.deleted) closed.value = true else show(n)
            loaded = true
            return@withLock
        }
        if (touched()) return@withLock // brugeren skriver: lad teksten stå, gem håndterer det
        if (n == null || n.deleted) {
            if (!discarded && n == null) {
                messages.tryEmit(R.string.deleted_elsewhere)
                closed.value = true
            }
            return@withLock
        }
        // Ændret af en sync, mens brugeren ikke har rørt noget: vis den nye version
        if (n.raw != baseRaw && !n.dirty) show(n)
    }

    private fun show(n: Note) {
        title = n.title
        body = n.body
        tagsText = n.tags.joinToString(", ")
        modified = n.modified
        baseline = Form.of(n)
        baseRaw = n.raw
    }

    fun onTitle(v: String) = changed { title = v }
    fun onTags(v: String) = changed { tagsText = v }
    fun onBody(v: String) = changed { body = v }

    private fun changed(set: () -> Unit) {
        set()
        status = if (touched()) SaveStatus.UNSAVED else if (status == SaveStatus.UNSAVED) SaveStatus.NONE else status
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1200)
            save()
        }
    }

    /** Gem nu, også selvom skærmen er ved at lukke. */
    fun flush() {
        saveJob?.cancel()
        if (discarded || !touched()) return
        app.appScope.launch(Dispatchers.Main) { save() }
    }

    private suspend fun save() = saveMutex.withLock {
        if (discarded || !loaded) return@withLock
        val form = form()
        val current = uid.value
        if (form == baseline) return@withLock
        if (current == null && form.isBlank()) return@withLock // en tom ny note oprettes ikke
        status = SaveStatus.SAVING
        val row = current?.let { withContext(Dispatchers.IO) { store.byUid(it) } }
        val saved: Note = withContext(Dispatchers.IO) {
            when {
                current == null -> store.add(form.title, form.body, form.tags)
                row == null || row.deleted -> {
                    // Slettet et andet sted, mens den var åben: opret den igen med brugerens tekst
                    if (row == null) messages.tryEmit(R.string.recreated)
                    store.add(form.title, form.body, form.tags)
                }
                row.raw != baseRaw && !row.dirty && !form.sameAs(row) -> {
                    // En sync har ændret noten, mens brugeren skrev: gem brugerens version som kopi
                    messages.tryEmit(R.string.conflict)
                    store.add("${form.title} ${app.getString(R.string.conflict_label)}".trim(), form.body, form.tags)
                }
                else -> store.edit(row.uid, form.title, form.body, form.tags) ?: row
            }
        }
        if (saved.uid != current) {
            uid.value = saved.uid
            hasNote = true
            if (title == form.title) title = saved.title // vis konfliktmærket i titlen
        }
        baseline = Form(saved.title, saved.body, saved.tags)
        baseRaw = saved.raw
        modified = saved.modified
        status = if (touched()) SaveStatus.UNSAVED else SaveStatus.SAVED
        app.requestSync()
    }

    fun delete() {
        saveJob?.cancel()
        discarded = true
        val current = uid.value
        app.appScope.launch {
            saveMutex.withLock {
                if (current != null) {
                    withContext(Dispatchers.IO) { store.deleteNote(current) }
                    app.requestSync()
                }
            }
        }
        closed.value = true
    }

    override fun onCleared() = flush()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(uid: String?, onBack: () -> Unit) {
    val app = noterApp(LocalContext.current)
    val vm: EditorViewModel = viewModel(factory = viewModelFactory { initializer { EditorViewModel(app, uid) } })
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val closed by vm.closed.collectAsStateWithLifecycle()

    LaunchedEffect(closed) { if (closed) onBack() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showMessage(app.getString(it)) }
    }
    ConflictMessages(app.syncManager.conflicts, snackbar)

    // Gem, når appen går i baggrunden
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.flush() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            vm.flush() // når man forlader skærmen
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    val s = when (vm.status) {
                        SaveStatus.UNSAVED -> stringResource(R.string.not_saved)
                        SaveStatus.SAVING -> stringResource(R.string.saving)
                        SaveStatus.SAVED -> stringResource(R.string.saved)
                        SaveStatus.NONE -> ""
                    }
                    Text(s, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                actions = {
                    if (vm.hasNote) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        val plain = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        )
        val serif = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif)
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
        ) {
            TextField(
                value = vm.title,
                onValueChange = vm::onTitle,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.headlineSmall,
                placeholder = { Text(stringResource(R.string.title), style = MaterialTheme.typography.headlineSmall) },
                colors = plain,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            TextField(
                value = vm.tagsText,
                onValueChange = vm::onTags,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.tertiary),
                placeholder = { Text(stringResource(R.string.tags_hint), style = MaterialTheme.typography.bodyMedium) },
                colors = plain,
                singleLine = true,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            TextField(
                value = vm.body,
                onValueChange = vm::onBody,
                modifier = Modifier.fillMaxWidth(),
                textStyle = serif,
                placeholder = { Text(stringResource(R.string.body_hint), style = serif) },
                colors = plain,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            DateLabels.parseStamp(vm.modified)?.let { t: Date ->
                Text(
                    stringResource(R.string.modified_at, DateLabels.fullTime(t)),
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = {
                Text(
                    if (vm.title.isBlank()) stringResource(R.string.delete_confirm_untitled)
                    else stringResource(R.string.delete_confirm, vm.title),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
