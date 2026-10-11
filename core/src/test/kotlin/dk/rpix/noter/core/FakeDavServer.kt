package dk.rpix.noter.core

/**
 * Lille CalDAV-server i hukommelsen med samme regler som Radicale for det, Syncer bruger:
 * etags, If-Match/If-None-Match, sync-collection med tokens og 404 for slettede objekter.
 */
class FakeDavServer(val collectionPath: String = "/anna/noter/", private val password: String = "hemmelig") {
    private class Obj(val etag: String, val raw: String)

    private val objects = LinkedHashMap<String, Obj>()
    private val log = mutableListOf<Pair<Int, String>>() // (seq, href)
    private var seq = 0
    private var etags = 0
    private var epoch = 0

    /** Alle tokens, der er givet ud indtil nu, bliver ugyldige. */
    @Synchronized fun invalidateTokens() { epoch++ }

    @Synchronized fun rawOf(href: String): String? = objects[href]?.raw

    private fun changed(href: String) { log.add(++seq to href) }

    /** [offline] = klienten kan ikke nå serveren. */
    fun client(password: String = this.password, offline: Boolean = false): DavApi = Client(password, offline)

    private inner class Client(private val pw: String, private val offline: Boolean) : DavApi {
        override val collectionPath get() = this@FakeDavServer.collectionPath

        private fun <T> call(block: () -> T): T = synchronized(this@FakeDavServer) {
            if (offline) throw NetworkError("Connection refused")
            if (pw != password) throw AuthError()
            block()
        }

        override fun syncCollection(token: String?) = call {
            val changes = if (token == null) {
                objects.map { (h, o) -> Change(h, o.etag) }
            } else {
                val parts = token.split("-")
                val k = parts.getOrNull(2)?.toIntOrNull()
                if (parts.size != 3 || parts[1] != "$epoch" || k == null || k > seq) throw SyncTokenInvalid()
                log.filter { it.first > k }.map { it.second }.distinct()
                    .map { h -> Change(h, objects[h]?.etag) }
            }
            SyncResponse(changes, "tok-$epoch-$seq")
        }

        override fun multiget(hrefs: List<String>) = call {
            hrefs.mapNotNull { h -> objects[h]?.let { Fetched(h, it.etag, it.raw) } }
        }

        override fun get(href: String) = call { objects[href]?.let { Fetched(href, it.etag, it.raw) } }

        override fun put(href: String, raw: String, etag: String?) = call {
            val cur = objects[href]
            when {
                etag == null && cur != null -> PutResult(412, null)
                etag != null && cur?.etag != etag -> PutResult(412, null)
                else -> {
                    val e = "\"e${++etags}\""
                    objects[href] = Obj(e, raw)
                    changed(href)
                    PutResult(if (cur == null) 201 else 204, e)
                }
            }
        }

        override fun delete(href: String, etag: String?) = call {
            val cur = objects[href]
            when {
                cur == null -> 404
                etag != null && cur.etag != etag -> 412
                else -> {
                    objects.remove(href)
                    changed(href)
                    204
                }
            }
        }
    }
}
