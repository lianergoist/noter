package dk.rpix.noter.data

import dk.rpix.noter.core.Note
import dk.rpix.noter.core.NoteStore
import dk.rpix.noter.core.ParsedNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [NoteStore] oven på Room. Blokerende; kaldes fra Dispatchers.IO. */
class RoomNoteStore(private val dao: NoteDao) : NoteStore {
    override fun byHref(href: String) = dao.byHref(href)?.toNote()
    override fun byUid(uid: String) = dao.byUid(uid)?.toNote()
    override fun pending() = dao.pending().map { it.toNote() }
    override fun withHref() = dao.withHref().map { it.toNote() }
    override fun visible() = dao.visible().map { it.toNote() }

    override fun getMeta(key: String) = dao.getMeta(key)
    override fun setMeta(key: String, value: String) = dao.setMeta(MetaEntity(key, value))

    override fun insert(
        uid: String, href: String?, etag: String?, raw: String, p: ParsedNote?,
        dirty: Boolean, ignored: Boolean,
    ) = dao.insert(entity(uid, href, etag, raw, p, dirty, ignored))

    override fun putClean(oldUid: String?, uid: String, href: String, etag: String, raw: String, p: ParsedNote?) =
        dao.putClean(oldUid, entity(uid, href, etag, raw, p, dirty = false, ignored = p == null))

    override fun markPushed(uid: String, href: String, etag: String) = dao.markPushed(uid, href, etag)
    override fun detach(uid: String) = dao.detach(uid)
    override fun remove(uid: String) = dao.remove(uid)
    override fun update(uid: String, raw: String, p: ParsedNote) =
        dao.update(uid, raw, p.title, p.body, p.tags, p.modified)

    override fun markDeleted(uid: String) = dao.markDeleted(uid)

    fun observeVisible(): Flow<List<Note>> = dao.observeVisible().map { l -> l.map { it.toNote() } }
    fun observe(uid: String): Flow<Note?> = dao.observe(uid).map { it?.toNote() }
    fun observePendingCount(): Flow<Int> = dao.observePendingCount()
    fun resetForNewCollection() = dao.resetForNewCollection()

    private fun entity(
        uid: String, href: String?, etag: String?, raw: String, p: ParsedNote?, dirty: Boolean, ignored: Boolean,
    ) = NoteEntity(
        uid = uid, href = href, etag = etag, raw = raw,
        title = p?.title ?: "", body = p?.body ?: "", tags = p?.tags ?: emptyList(),
        modified = p?.modified ?: "", dirty = dirty, deleted = false, ignored = ignored,
    )
}
