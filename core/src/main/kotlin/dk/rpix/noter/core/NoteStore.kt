package dk.rpix.noter.core

import java.util.UUID

/** Én række i det lokale lager. Port af `Note` i reference/notesync.py. */
data class Note(
    val uid: String,
    val href: String?,
    val etag: String?,
    val raw: String,
    val title: String,
    val body: String,
    val tags: List<String>,
    val modified: String,
    val dirty: Boolean,
    val deleted: Boolean,
    val ignored: Boolean,
)

/**
 * Lokalt lager (svarer til `Store` i notesync.py). Room-implementeringen ligger i :app,
 * [MemoryNoteStore] bruges i tests. Alle kald er blokerende; kald dem ikke fra UI-tråden.
 */
interface NoteStore {
    // -- opslag ------------------------------------------------------------
    fun byHref(href: String): Note?
    fun byUid(uid: String): Note?

    /** `dirty AND NOT ignored` */
    fun pending(): List<Note>
    fun withHref(): List<Note>

    /** `deleted = 0 AND ignored = 0`, sorteret efter `modified` faldende, derefter titel. */
    fun visible(): List<Note>

    fun getMeta(key: String): String?
    fun setMeta(key: String, value: String)

    // -- skrivning ---------------------------------------------------------
    /** INSERT OR REPLACE med `deleted = 0`. [p] = null giver tomme felter (ignoreret objekt). */
    fun insert(
        uid: String, href: String?, etag: String?, raw: String, p: ParsedNote?,
        dirty: Boolean = false, ignored: Boolean = false,
    )

    /** Erstat en række med serverens version (dirty=0, deleted=0). Skal ske atomart. */
    fun putClean(oldUid: String?, uid: String, href: String, etag: String, raw: String, p: ParsedNote?)

    fun markPushed(uid: String, href: String, etag: String)

    /** Serveren har mistet noten, men vi har lokale ændringer: opret den igen. */
    fun detach(uid: String)

    fun remove(uid: String)

    /** Gem en redigeret version (`dirty = 1`). */
    fun update(uid: String, raw: String, p: ParsedNote)

    /** `deleted = 1, dirty = 1` */
    fun markDeleted(uid: String)

    // -- lokale handlinger (ingen netværk) -----------------------------------
    fun add(title: String, body: String = "", tags: List<String> = emptyList()): Note {
        val uid = UUID.randomUUID().toString()
        val raw = Ical.newIcs(uid, title, body, tags)
        insert(uid, null, null, raw, Ical.parseIcs(raw), dirty = true)
        return byUid(uid)!!
    }

    /** null = feltet er urørt. Er intet ændret, sker der ingenting. */
    fun edit(uid: String, title: String? = null, body: String? = null, tags: List<String>? = null): Note? {
        val n = byUid(uid) ?: return null
        val raw = Ical.updateIcs(n.raw, title, body, tags)
        if (raw != n.raw) update(uid, raw, Ical.parseIcs(raw))
        return byUid(uid)
    }

    fun deleteNote(uid: String) {
        val n = byUid(uid) ?: return
        if (n.href == null) remove(uid) // aldrig sendt til serveren
        else markDeleted(uid)
    }
}

/** Søgning i titel, tekst og tags uden forskel på store og små bogstaver. */
fun List<Note>.search(query: String): List<Note> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { n ->
        n.title.contains(q, ignoreCase = true) || n.body.contains(q, ignoreCase = true) ||
            n.tags.any { it.contains(q, ignoreCase = true) }
    }
}

/** Lager i hukommelsen, til tests. */
class MemoryNoteStore : NoteStore {
    private val notes = LinkedHashMap<String, Note>()
    private val meta = HashMap<String, String>()

    @Synchronized override fun byHref(href: String) = notes.values.firstOrNull { it.href == href }
    @Synchronized override fun byUid(uid: String) = notes[uid]
    @Synchronized override fun pending() = notes.values.filter { it.dirty && !it.ignored }
    @Synchronized override fun withHref() = notes.values.filter { it.href != null }

    @Synchronized override fun visible() = notes.values
        .filter { !it.deleted && !it.ignored }
        .sortedWith(compareByDescending<Note> { it.modified }.thenBy { it.title })

    @Synchronized override fun getMeta(key: String) = meta[key]
    @Synchronized override fun setMeta(key: String, value: String) { meta[key] = value }

    @Synchronized override fun insert(
        uid: String, href: String?, etag: String?, raw: String, p: ParsedNote?,
        dirty: Boolean, ignored: Boolean,
    ) {
        // Som SQLite's INSERT OR REPLACE: en anden række med samme href (unikt indeks) fjernes
        if (href != null) notes.values.removeAll { it.href == href && it.uid != uid }
        notes[uid] = Note(
            uid, href, etag, raw, p?.title ?: "", p?.body ?: "", p?.tags ?: emptyList(),
            p?.modified ?: "", dirty, deleted = false, ignored = ignored,
        )
    }

    @Synchronized override fun putClean(
        oldUid: String?, uid: String, href: String, etag: String, raw: String, p: ParsedNote?,
    ) {
        if (oldUid != null) notes.remove(oldUid)
        insert(uid, href, etag, raw, p, dirty = false, ignored = p == null)
    }

    @Synchronized override fun markPushed(uid: String, href: String, etag: String) {
        notes[uid]?.let { notes[uid] = it.copy(href = href, etag = etag, dirty = false) }
    }

    @Synchronized override fun detach(uid: String) {
        notes[uid]?.let { notes[uid] = it.copy(href = null, etag = null, dirty = true, deleted = false) }
    }

    @Synchronized override fun remove(uid: String) { notes.remove(uid) }

    @Synchronized override fun update(uid: String, raw: String, p: ParsedNote) {
        notes[uid]?.let {
            notes[uid] = it.copy(raw = raw, title = p.title, body = p.body, tags = p.tags, modified = p.modified, dirty = true)
        }
    }

    @Synchronized override fun markDeleted(uid: String) {
        notes[uid]?.let { notes[uid] = it.copy(deleted = true, dirty = true) }
    }
}
