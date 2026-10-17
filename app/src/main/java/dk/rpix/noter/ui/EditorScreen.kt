package dk.rpix.noter.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
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
import dk.rpix.noter.core.Markdown
import dk.rpix.noter.core.MdEdit
import dk.rpix.noter.core.Note
import dk.rpix.noter.core.NoteFile
import dk.rpix.noter.data.FileImport
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
    var bodyValue by mutableStateOf(TextFieldValue(""))
        private set
    val body get() = bodyValue.text
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
        bodyValue = TextFieldValue(n.body, TextRange(minOf(bodyValue.selection.end, n.body.length)))
        tagsText = n.tags.joinToString(", ")
        modified = n.modified
        baseline = Form.of(n)
        baseRaw = n.raw
    }

    fun onTitle(v: String) = changed { title = v }
    fun onTags(v: String) = changed { tagsText = v }
    fun onBody(v: TextFieldValue) {
        // Kun markør eller markering flyttet: intet at gemme
        if (v.text == bodyValue.text) {
            bodyValue = v
            return
        }
        val next = continuedList(v) ?: v
        changed { bodyValue = next }
    }

    /** Var ændringen ét Enter, så fortsæt eller afslut listen (se Markdown.continueList). */
    private fun continuedList(v: TextFieldValue): TextFieldValue? {
        val old = bodyValue.text
        val c = v.selection.start
        if (!v.selection.collapsed || v.text.length != old.length + 1 || c == 0 || v.text[c - 1] != '\n') return null
        if (v.text.removeRange(c - 1, c) != old) return null
        val r = Markdown.continueList(v.text, c) ?: return null
        return TextFieldValue(r.text, TextRange(r.selStart, r.selEnd))
    }

    /** En knap på værktøjslinjen. */
    fun format(op: (String, Int, Int) -> MdEdit) {
        val sel = bodyValue.selection
        val r = op(body, sel.min, sel.max)
        onBody(TextFieldValue(r.text, TextRange(r.selStart, r.selEnd)))
    }

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
    var bodyFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Gem som fil: indholdet tages, når filvælgeren åbnes
    var pendingFile by remember { mutableStateOf("") }
    val onPicked: (Uri?) -> Unit = { uri ->
        if (uri != null) scope.launch {
            val ok = FileImport.write(app, uri, pendingFile)
            snackbar.showMessage(app.getString(if (ok) R.string.file_saved else R.string.file_save_failed))
        }
    }
    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), onPicked)
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), onPicked)
    val fallbackName = stringResource(R.string.untitled_file)

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showMessage(app.getString(R.string.no_browser)) }
        }
    }
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
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_as_markdown)) },
                            onClick = {
                                menuOpen = false
                                pendingFile = NoteFile.toMarkdown(vm.title, vm.body)
                                saveMarkdown.launch(NoteFile.fileName(vm.title, "md", fallbackName))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_as_text)) },
                            onClick = {
                                menuOpen = false
                                pendingFile = NoteFile.toText(vm.body)
                                saveText.launch(NoteFile.fileName(vm.title, "txt", fallbackName))
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (bodyFocused) {
                val sel = vm.bodyValue.selection
                FormatBar(url = Markdown.urlAt(vm.body, sel.min, sel.max), onOpenUrl = ::openUrl, onFormat = vm::format)
            }
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
            Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()
                .verticalScroll(rememberScrollState()),
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
            val markdown = MarkdownTransformation(
                marker = MaterialTheme.colorScheme.outline,
                bullet = MaterialTheme.colorScheme.onSurfaceVariant,
                link = MaterialTheme.colorScheme.primary,
            )
            TextField(
                value = vm.bodyValue,
                onValueChange = vm::onBody,
                modifier = Modifier.fillMaxWidth().onFocusChanged { bodyFocused = it.isFocused },
                textStyle = serif,
                visualTransformation = markdown,
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
