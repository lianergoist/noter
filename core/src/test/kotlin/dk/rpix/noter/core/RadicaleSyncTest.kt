package dk.rpix.noter.core

import java.net.ServerSocket
import java.util.UUID
import org.junit.Assume.assumeTrue

/**
 * Scenarierne mod en rigtig Radicale (se "Test" i docs/SPEC.md). Springes over, hvis
 * NOTES_TEST_URL ikke er sat. NOTES_TEST_URL er en samling, fx http://127.0.0.1:5232/anna/noter/;
 * hver test bruger en ny søstersamling, som slettes bagefter.
 * Bruger og adgangskode: NOTES_TEST_USER og NOTES_TEST_PASSWORD (standard anna/hemmelig).
 */
class RadicaleSyncTest : SyncScenarios() {
    override fun backend(): TestBackend {
        val base = System.getenv("NOTES_TEST_URL")
        assumeTrue("NOTES_TEST_URL er ikke sat", !base.isNullOrBlank())
        return RadicaleBackend(base!!)
    }
}

class RadicaleBackend(base: String) : TestBackend {
    private val user = System.getenv("NOTES_TEST_USER") ?: "anna"
    private val password = System.getenv("NOTES_TEST_PASSWORD") ?: "hemmelig"
    private val url = Hrefs.withSlash(base).trimEnd('/').substringBeforeLast('/') +
        "/noter-test-" + UUID.randomUUID().toString().take(8) + "/"
    private val admin = DavClient(url, user, password, allowInsecure = true, timeoutSeconds = 10)

    init {
        admin.mkcalendar("Noter (test)")
    }

    override fun client(password: String?) =
        DavClient(url, user, password ?: this.password, allowInsecure = true, timeoutSeconds = 10)

    override fun offlineClient(): DavApi {
        val port = ServerSocket(0).use { it.localPort }
        val dead = url.replace(Regex("^(https?://)[^/]+"), "$1127.0.0.1:$port")
        return DavClient(dead, user, password, allowInsecure = true, timeoutSeconds = 2)
    }

    override val invalidToken = "http://radicale.org/ns/sync/ugyldig"

    override fun close() {
        runCatching { admin.request("DELETE", admin.collectionPath) }
    }
}
