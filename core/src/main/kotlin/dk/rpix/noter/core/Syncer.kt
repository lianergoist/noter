package dk.rpix.noter.core

import java.util.UUID

data class SyncStats(
    var fetched: Int = 0,
    var deletedLocally: Int = 0,
    var pushed: Int = 0,
    var deletedOnServer: Int = 0,
    var conflicts: Int = 0,
)

/**
 * Pull -> push med etag-baseret optimistisk låsning og konfliktkopi.
 * Port af `Syncer` i reference/notesync.py. Blokerende; højst én sync ad gangen pr. lager
 * (det sørger kalderen for).
 */
class Syncer(private val store: NoteStore, private val client: DavApi) {
    val stats = SyncStats()

    fun sync(): SyncStats {
        pull()
        push()
        return stats
    }

    // -- pull --------------------------------------------------------------
    fun pull() {
        val token = store.getMeta(SYNC_TOKEN)
        var full = token == null
        val res = try {
            client.syncCollection(token)
        } catch (_: SyncTokenInvalid) {
            full = true
            client.syncCollection(null)
        }

        val toFetch = mutableListOf<String>()
        for ((href, etag) in res.changes) {
            val local = store.byHref(href)
            if (etag == null) {
                if (local != null) serverDeleted(local)
            } else if (local == null || local.etag != etag) {
                toFetch.add(href)
            }
        }

        if (full) { // fuld resync: alt, vi kender, men serveren ikke nævner, er væk
            val onServer = res.changes.filter { it.etag != null }.map { it.href }.toSet()
            for (local in store.withHref()) {
                if (local.href !in onServer) serverDeleted(local)
            }
        }

        for ((href, etag, raw) in client.multiget(toFetch)) adopt(href, etag, raw)
        store.setMeta(SYNC_TOKEN, res.token)
    }

    private fun serverDeleted(local: Note) {
        if (local.dirty && !local.deleted) {
            store.detach(local.uid) // redigeret lokalt, slettet andetsteds: behold
        } else {
            store.remove(local.uid)
            stats.deletedLocally++
        }
    }

    /**
     * Tag serverens version ind. Er noten også ændret lokalt, gemmes den lokale
     * version som en ny note med '(konflikt)' i titlen.
     */
    private fun adopt(href: String, etag: String, raw: String) {
        val p = try {
            Ical.parseIcs(raw)
        } catch (_: IcalException) {
            val old = store.byHref(href)
            store.putClean(old?.uid, "href:$href", href, etag, raw, null)
            return
        }
        val local = store.byHref(href) ?: store.byUid(p.uid)
        stats.fetched++
        if (local != null && local.dirty && !local.deleted) {
            if (Triple(local.title, local.body, local.tags) != Triple(p.title, p.body, p.tags)) {
                stats.conflicts++
                val copyUid = UUID.randomUUID().toString()
                val copyRaw = Ical.newIcs(copyUid, "${local.title} (konflikt)".trim(), local.body, local.tags)
                store.insert(copyUid, null, null, copyRaw, Ical.parseIcs(copyRaw), dirty = true)
            }
        }
        store.putClean(local?.uid, p.uid, href, etag, raw, p)
    }

    // -- push --------------------------------------------------------------
    fun push() {
        repeat(5) {
            val pending = store.pending()
            if (pending.isEmpty()) return
            for (n in pending) pushOne(n)
        }
        if (store.pending().isEmpty()) return
        throw DavError(0, "lokale ændringer kunne ikke sendes efter 5 forsøg")
    }

    private fun pushOne(n: Note) {
        if (n.deleted) {
            if (n.href == null) {
                store.remove(n.uid)
                return
            }
            when (val code = client.delete(n.href, n.etag)) {
                200, 204, 404 -> {
                    store.remove(n.uid)
                    stats.deletedOnServer++
                }
                412 -> resolve412(n, n.href)
                else -> throw DavError(code, "DELETE ${n.href}")
            }
            return
        }
        val create = n.href == null
        val href = n.href ?: (client.collectionPath + n.uid + ".ics")
        val r = client.put(href, n.raw, if (create) null else n.etag)
        when (r.status) {
            200, 201, 204 -> {
                store.markPushed(n.uid, href, r.etag!!)
                stats.pushed++
            }
            412 -> resolve412(n, href)
            else -> throw DavError(r.status, "PUT $href")
        }
    }

    private fun resolve412(n: Note, href: String) {
        val got = client.get(href)
        if (got == null) { // findes ikke længere på serveren
            if (n.deleted) store.remove(n.uid)
            else store.detach(n.uid) // oprettes igen i næste runde
            return
        }
        adopt(href, got.etag, got.raw)
    }

    companion object {
        const val SYNC_TOKEN = "sync_token"
    }
}
