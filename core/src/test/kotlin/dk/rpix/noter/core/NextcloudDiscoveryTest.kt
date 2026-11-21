package dk.rpix.noter.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #6: en server indrettet som Nextcloud (Sabre). Roden kan ikke PROPFIND (501),
 * `/.well-known/caldav` omdirigerer til `/remote.php/dav/`, og kalenderne ligger under
 * `calendar-home-set`, ikke under principal.
 */
class NextcloudDiscoveryTest {
    private val dav = "/remote.php/dav/"
    private val principal = "${dav}principals/users/anna/"
    private val home = "${dav}calendars/anna/"
    private val calendars = mutableMapOf(
        "${home}personal/" to listOf("VEVENT", "VTODO"),
        "${home}noter/" to listOf("VJOURNAL"),
    )

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val depth = ex.requestHeaders.getFirst("Depth")
            ex.requestBody.readBytes()
            if (path == "/.well-known/caldav") {
                ex.responseHeaders.add("Location", dav)
                ex.sendResponseHeaders(301, -1)
                ex.close()
                return@createContext
            }
            val (status, body) = when {
                ex.requestMethod != "PROPFIND" -> 405 to ""
                !path.startsWith(dav) -> 501 to "" // som Nextclouds rod
                path == home && depth == "1" -> 207 to listing()
                path == dav || path == principal || path == home || path in calendars -> 207 to props(path)
                else -> 404 to ""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        start()
    }

    private fun response(path: String, inner: String) =
        "<d:response><d:href>$path</d:href><d:propstat><d:prop>$inner</d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun multistatus(vararg responses: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">""" +
            responses.joinToString("") + "</d:multistatus>"

    private fun props(path: String) = multistatus(
        response(
            path,
            "<d:current-user-principal><d:href>$principal</d:href></d:current-user-principal>" +
                (if (path == principal) "<cal:calendar-home-set><d:href>$home</d:href></cal:calendar-home-set>" else "") +
                "<d:resourcetype><d:collection/></d:resourcetype>",
        ),
    )

    private fun listing() = multistatus(
        response(home, "<d:resourcetype><d:collection/></d:resourcetype>"),
        *calendars.map { (p, comps) ->
            response(
                p,
                "<d:displayname>${p.trimEnd('/').substringAfterLast('/')}</d:displayname>" +
                    "<d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>" +
                    "<cal:supported-calendar-component-set>" +
                    comps.joinToString("") { """<cal:comp name="$it"/>""" } +
                    "</cal:supported-calendar-component-set>",
            )
        }.toTypedArray(),
    )

    private fun client(path: String) =
        DavClient("http://127.0.0.1:${server.address.port}$path", "anna", "hemmelig", allowInsecure = true, timeoutSeconds = 5)

    @After fun tearDown() = server.stop(0)

    @Test fun principalFoundViaWellKnown() {
        assertEquals(principal, client("/").principal())
    }

    @Test fun principalFoundFromCollectionAddress() {
        assertEquals(principal, client("${home}noter/").principal())
    }

    @Test fun discoverLooksUnderCalendarHome() {
        for (start in listOf("/", dav, "${home}personal/")) {
            assertEquals(start, listOf("${home}noter/"), client(start).discover().map { it.path })
        }
    }

    @Test fun newCollectionGoesUnderCalendarHome() {
        assertEquals("${home}noter-2/", client("/").newCollectionPath())
        assertEquals("${home}noter-2/", client(dav).newCollectionPath())
        assertEquals("${home}noter-2/", client(principal).newCollectionPath())
        assertEquals("${home}noter-2/", client(home).newCollectionPath())
        assertEquals("${home}dagbog/", client("${home}dagbog/").newCollectionPath())
    }
}
