package dk.rpix.noter.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Hvor "Opret noter-samling" lægger samlingen, og fejlen ved en server uden CalDAV.
 * Kører mod en lille falsk server, så den ikke kræver Radicale.
 */
class NewCollectionTest {
    /** Stier, der findes; resten giver 404. null = serveren oplyser ingen principal. */
    private var principal: String? = "/anna/"
    private val existing = mutableSetOf("/", "/anna/")
    private var propfindStatus = 207
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val (status, body) = when {
                ex.requestMethod != "PROPFIND" -> 405 to ""
                propfindStatus != 207 -> propfindStatus to ""
                path !in existing -> 404 to ""
                else -> 207 to multistatus(path)
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        start()
    }

    private fun multistatus(path: String): String {
        val cup = principal?.let { "<D:current-user-principal><D:href>$it</D:href></D:current-user-principal>" } ?: ""
        return """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>$path</D:href>""" +
            """<D:propstat><D:prop>$cup<D:resourcetype><D:collection/></D:resourcetype></D:prop>""" +
            "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"
    }

    private fun client(path: String) =
        DavClient("http://127.0.0.1:${server.address.port}$path", "anna", "hemmelig", allowInsecure = true, timeoutSeconds = 5)

    @After fun tearDown() = server.stop(0)

    @Test fun rootAddressCreatesUnderPrincipal() {
        assertEquals("/anna/noter/", client("/").newCollectionPath())
    }

    @Test fun principalAddressCreatesUnderPrincipal() {
        assertEquals("/anna/noter/", client("/anna/").newCollectionPath())
    }

    @Test fun takenNameGetsNumber() {
        existing += "/anna/noter/"
        assertEquals("/anna/noter-2/", client("/").newCollectionPath())
    }

    @Test fun collectionAddressIsUsedAsIs() {
        assertEquals("/anna/dagbog/", client("/anna/dagbog/").newCollectionPath())
    }

    @Test fun urlForKeepsServerAndEncodes() {
        val c = client("/")
        assertEquals("http://127.0.0.1:${server.address.port}/anna/n%C3%B8gle/", c.urlFor("/anna/nøgle/"))
    }

    @Test fun serverWithoutPrincipalIsNotCalDav() {
        principal = null
        assertThrows(NotCalDavError::class.java) { client("/").discover() }
        assertThrows(NotCalDavError::class.java) { client("/").newCollectionPath() }
    }

    @Test fun serverWithoutPropfindIsNotCalDav() {
        propfindStatus = 405
        assertThrows(NotCalDavError::class.java) { client("/").discover() }
    }
}
