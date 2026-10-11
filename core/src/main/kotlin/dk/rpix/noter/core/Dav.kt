package dk.rpix.noter.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource

// ============================================================================
// Fejl
// ============================================================================

/** Serveren kunne ikke nås (offline, DNS, timeout ...). Ændringer er allerede gemt lokalt. */
class NetworkError(message: String, cause: Throwable? = null) : Exception(message, cause)

open class DavError(val status: Int, message: String = "") :
    Exception(if (status != 0) "HTTP $status: $message" else message)

class AuthError(message: String = "forkert brugernavn eller adgangskode") : DavError(401, message)

/** Serveren afviser et gammelt sync-token (HTTP 403, 409 eller 410). */
class SyncTokenInvalid : Exception("ugyldigt sync-token")

// ============================================================================
// Det, Syncer har brug for fra serveren
// ============================================================================

/** etag = null betyder slettet på serveren. */
data class Change(val href: String, val etag: String?)

data class SyncResponse(val changes: List<Change>, val token: String)

data class Fetched(val href: String, val etag: String, val raw: String)

/** etag er kun sat ved 200/201/204. */
data class PutResult(val status: Int, val etag: String?)

interface DavApi {
    /** Afkodet sti til samlingen, med / til sidst. */
    val collectionPath: String

    /** RFC 6578. Kaster [SyncTokenInvalid], hvis [token] afvises. */
    fun syncCollection(token: String?): SyncResponse

    fun multiget(hrefs: List<String>): List<Fetched>

    /** null = findes ikke (404). */
    fun get(href: String): Fetched?

    /** etag = null -> opret (If-None-Match: *), ellers opdatér (If-Match). */
    fun put(href: String, raw: String, etag: String?): PutResult

    /** Returnerer HTTP-status. */
    fun delete(href: String, etag: String?): Int
}

/** En samling fundet via [DavClient.discover]. */
data class CollectionInfo(
    val path: String,
    val name: String,
    val comps: List<String>,
) {
    val journal get() = "VJOURNAL" in comps
    val onlyJournal get() = comps == listOf("VJOURNAL")
}

// ============================================================================
// Hjælpere til stier og XML
// ============================================================================

private const val DAV = "DAV:"
private const val CAL = "urn:ietf:params:xml:ns:caldav"
private const val XML_HEAD = """<?xml version="1.0" encoding="utf-8"?>"""
private val XML_TYPE = "application/xml; charset=utf-8".toMediaType()
private val ICS_TYPE = "text/calendar; charset=utf-8".toMediaType()

object Hrefs {
    /** Gem altid hrefs som afkodede stier, så sammenligning er entydig. */
    fun normalize(href: String): String {
        var path = href.trim()
        if ("://" in path) path = runCatching { URI(path).rawPath }.getOrNull() ?: path
        path = path.substringBefore('?').substringBefore('#')
        return percentDecode(path)
    }

    /** Kodér pr. stisegment (som Pythons `quote`, `/` bevares). */
    fun encodePath(path: String): String {
        val sb = StringBuilder()
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c.toChar().isUnreserved() || c == '/'.code) sb.append(c.toChar())
            else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    private fun Char.isUnreserved() =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"

    /** Som Pythons `unquote`: kun %XX, og `+` er ikke mellemrum. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s[i + 1].isHex() && s[i + 2].isHex()) {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                // Skriv frem til næste % i ét stykke, så surrogatpar (emoji) ikke deles
                val j = s.indexOf('%', i + 1).let { if (it < 0) s.length else it }
                out.write(s.substring(i, j).toByteArray(Charsets.UTF_8))
                i = j
            }
        }
        return out.toString("UTF-8")
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    fun withSlash(p: String) = if (p.endsWith("/")) p else "$p/"
}

private fun xmlEscape(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun parseXml(body: ByteArray): Element {
    try {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // Ingen DTD'er eller eksterne entiteter fra serveren
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(InputSource(body.inputStream())).documentElement
    } catch (e: Exception) {
        throw DavError(0, "ugyldigt XML-svar fra serveren: ${e.message}")
    }
}

/** Kun direkte børn, så en indlejret href ikke forveksles. */
private fun Element.kids(ns: String, name: String): List<Element> {
    val out = mutableListOf<Element>()
    var n = firstChild
    while (n != null) {
        if (n is Element && n.namespaceURI == ns && n.localName == name) out.add(n)
        n = n.nextSibling
    }
    return out
}

private fun Element.kid(ns: String, name: String): Element? = kids(ns, name).firstOrNull()

/** Tekst i en sti af direkte børn, fx `kidText(DAV to "prop", DAV to "getetag")`. */
private fun Element.kidText(vararg path: Pair<String, String>): String? {
    var el: Element = this
    for ((ns, name) in path) el = el.kid(ns, name) ?: return null
    return el.textContent
}

/** Alle `prop`-elementer fra propstats med status 200. */
private fun Element.okProps(): List<Element> =
    kids(DAV, "propstat")
        .filter { " 200" in (it.kidText(DAV to "status") ?: "") }
        .mapNotNull { it.kid(DAV, "prop") }

// ============================================================================
// CalDAV-klienten
// ============================================================================

/**
 * Lille CalDAV-klient til VJOURNAL-noter. Port af `DavClient` i reference/notesync.py.
 *
 * [allowInsecure] tillader `http://`; brug det kun til en lokal testserver.
 * Adgangskoden lægges på af en interceptor og logges aldrig.
 */
class DavClient(
    collectionUrl: String,
    user: String,
    password: String,
    allowInsecure: Boolean = false,
    timeoutSeconds: Long = 30,
    baseClient: OkHttpClient? = null,
) : DavApi {
    val collectionUrl: HttpUrl
    private val origin: String
    override val collectionPath: String
    private val http: OkHttpClient

    init {
        val url = try {
            Hrefs.withSlash(collectionUrl.trim()).toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("ugyldig adresse: $collectionUrl", e)
        }
        require(url.isHttps || allowInsecure) { "adressen skal begynde med https://" }
        this.collectionUrl = url
        val defaultPort = HttpUrl.defaultPort(url.scheme)
        origin = "${url.scheme}://${hostForUrl(url.host)}" + (if (url.port != defaultPort) ":${url.port}" else "")
        collectionPath = Hrefs.normalize(url.encodedPath)
        val auth = Credentials.basic(user, password, Charsets.UTF_8)
        http = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
            .cache(null)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", auth).build())
            }
            .build()
    }

    private fun hostForUrl(host: String) = if (':' in host) "[$host]" else host

    class Response(val status: Int, val headers: Headers, val body: ByteArray)

    private fun url(path: String): HttpUrl =
        if ("://" in path) path.toHttpUrl() else (origin + Hrefs.encodePath(path)).toHttpUrl()

    fun request(method: String, path: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): Response {
        val b = Request.Builder().url(url(path))
        headers.forEach { (k, v) -> b.header(k, v) }
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, body ?: ByteArray(0).toRequestBody(null))
        }
        try {
            http.newCall(b.build()).execute().use { r ->
                val data = r.body.bytes()
                if (r.code == 401) throw AuthError()
                return Response(r.code, r.headers, data)
            }
        } catch (e: IOException) {
            throw NetworkError(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun xml(method: String, path: String, xml: String, depth: String? = null): Response =
        request(method, path, (XML_HEAD + xml).toRequestBody(XML_TYPE), if (depth != null) mapOf("Depth" to depth) else emptyMap())

    private fun snippet(b: ByteArray) = String(b, Charsets.UTF_8).take(200)

    // -- samling -----------------------------------------------------------
    fun mkcalendar(displayname: String = "Noter") {
        val body = """<C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">""" +
            "<D:set><D:prop><D:displayname>${xmlEscape(displayname)}</D:displayname>" +
            """<C:supported-calendar-component-set><C:comp name="VJOURNAL"/>""" +
            "</C:supported-calendar-component-set></D:prop></D:set></C:mkcalendar>"
        val r = xml("MKCALENDAR", collectionPath, body)
        if (r.status != 200 && r.status != 201) throw DavError(r.status, snippet(r.body))
    }

    /**
     * Tjek login og at samlingen findes (PROPFIND Depth 0). false = 404.
     * Kaster [AuthError], [NetworkError] eller [DavError] som de andre kald.
     */
    fun collectionExists(): Boolean {
        val r = xml("PROPFIND", collectionPath, """<D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>""", "0")
        return when (r.status) {
            207 -> true
            404 -> false
            else -> throw DavError(r.status, "PROPFIND $collectionPath")
        }
    }

    /**
     * Find brugerens kalendersamlinger. Vælg dem med VJOURNAL, helst dem der kun har VJOURNAL.
     * Port af `discover` i reference/dav.js.
     */
    fun discover(): List<CollectionInfo> {
        var r = xml("PROPFIND", "/", """<D:propfind xmlns:D="DAV:"><D:prop><D:current-user-principal/></D:prop></D:propfind>""", "0")
        if (r.status != 207) throw DavError(r.status, "PROPFIND /")
        val cup = parseXml(r.body).getElementsByTagNameNS(DAV, "current-user-principal").item(0) as Element?
        val href = (cup?.getElementsByTagNameNS(DAV, "href")?.item(0) as Element?)?.textContent
            ?: throw DavError(0, "kunne ikke finde din bruger på serveren")
        val principal = Hrefs.withSlash(Hrefs.normalize(href))

        r = xml(
            "PROPFIND", principal,
            """<D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><D:prop>""" +
                "<D:displayname/><D:resourcetype/><C:supported-calendar-component-set/></D:prop></D:propfind>",
            "1",
        )
        if (r.status != 207) throw DavError(r.status, "PROPFIND $principal")
        val out = mutableListOf<CollectionInfo>()
        for (resp in parseXml(r.body).kids(DAV, "response")) {
            val path = Hrefs.withSlash(Hrefs.normalize(resp.kidText(DAV to "href") ?: continue))
            if (path == principal) continue
            val props = resp.okProps()
            fun find(ns: String, name: String) = props.firstNotNullOfOrNull { it.kid(ns, name) }
            val rt = find(DAV, "resourcetype") ?: continue
            if (rt.kids(CAL, "calendar").isEmpty()) continue
            val comps = find(CAL, "supported-calendar-component-set")
                ?.kids(CAL, "comp")?.map { it.getAttribute("name").uppercase() } ?: emptyList()
            val name = find(DAV, "displayname")?.textContent?.takeIf { it.isNotBlank() }
                ?: path.split("/").lastOrNull { it.isNotEmpty() } ?: path
            out.add(CollectionInfo(path, name, comps))
        }
        return out
            .filter { it.journal }
            .sortedWith(compareByDescending<CollectionInfo> { it.onlyJournal }.thenBy { it.name })
    }

    fun getEtag(href: String): String {
        val r = xml("PROPFIND", href, """<D:propfind xmlns:D="DAV:"><D:prop><D:getetag/></D:prop></D:propfind>""", "0")
        if (r.status != 207) throw DavError(r.status, "kunne ikke hente etag")
        val etag = parseXml(r.body).getElementsByTagNameNS(DAV, "getetag").item(0)?.textContent
        if (etag.isNullOrEmpty()) throw DavError(r.status, "svar uden etag")
        return etag
    }

    // -- synkronisering ----------------------------------------------------
    override fun syncCollection(token: String?): SyncResponse {
        val tok = if (token != null) "<D:sync-token>${xmlEscape(token)}</D:sync-token>" else "<D:sync-token/>"
        val body = """<D:sync-collection xmlns:D="DAV:">$tok<D:sync-level>1</D:sync-level>""" +
            "<D:prop><D:getetag/></D:prop></D:sync-collection>"
        val r = xml("REPORT", collectionPath, body)
        if (token != null && r.status in setOf(403, 409, 410)) throw SyncTokenInvalid()
        if (r.status != 207) throw DavError(r.status, snippet(r.body))
        val root = parseXml(r.body)
        val changes = mutableListOf<Change>()
        for (resp in root.kids(DAV, "response")) {
            val href = Hrefs.normalize(resp.kidText(DAV to "href") ?: "")
            if (href.endsWith("/")) continue // selve samlingen
            if (" 404" in (resp.kidText(DAV to "status") ?: "")) {
                changes.add(Change(href, null))
                continue
            }
            var etag: String? = null
            for (ps in resp.kids(DAV, "propstat")) {
                if (" 200" in (ps.kidText(DAV to "status") ?: "")) {
                    etag = ps.kidText(DAV to "prop", DAV to "getetag")
                }
            }
            if (!etag.isNullOrEmpty()) changes.add(Change(href, etag))
        }
        val newToken = root.kidText(DAV to "sync-token")
        if (newToken.isNullOrEmpty()) throw DavError(r.status, "svar uden sync-token")
        return SyncResponse(changes, newToken)
    }

    override fun multiget(hrefs: List<String>): List<Fetched> {
        val out = mutableListOf<Fetched>()
        for (chunk in hrefs.chunked(50)) {
            val items = chunk.joinToString("") { "<D:href>${xmlEscape(Hrefs.encodePath(it))}</D:href>" }
            val body = """<C:calendar-multiget xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">""" +
                "<D:prop><D:getetag/><C:calendar-data/></D:prop>$items</C:calendar-multiget>"
            val r = xml("REPORT", collectionPath, body, "1")
            if (r.status != 207) throw DavError(r.status, snippet(r.body))
            for (resp in parseXml(r.body).kids(DAV, "response")) {
                val href = Hrefs.normalize(resp.kidText(DAV to "href") ?: "")
                for (ps in resp.kids(DAV, "propstat")) {
                    if (" 200" !in (ps.kidText(DAV to "status") ?: "")) continue
                    val etag = ps.kidText(DAV to "prop", DAV to "getetag")
                    val data = ps.kidText(DAV to "prop", CAL to "calendar-data")
                    if (!etag.isNullOrEmpty() && !data.isNullOrEmpty()) out.add(Fetched(href, etag, data))
                }
            }
        }
        return out
    }

    // -- enkeltobjekter ----------------------------------------------------
    override fun get(href: String): Fetched? {
        val r = request("GET", href)
        if (r.status == 404) return null
        if (r.status != 200) throw DavError(r.status, "GET $href")
        return Fetched(href, r.headers["ETag"] ?: getEtag(href), String(r.body, Charsets.UTF_8))
    }

    override fun put(href: String, raw: String, etag: String?): PutResult {
        val headers = if (etag != null) mapOf("If-Match" to etag) else mapOf("If-None-Match" to "*")
        val r = request("PUT", href, raw.toByteArray(Charsets.UTF_8).toRequestBody(ICS_TYPE), headers)
        if (r.status in setOf(200, 201, 204)) return PutResult(r.status, r.headers["ETag"] ?: getEtag(href))
        return PutResult(r.status, null)
    }

    override fun delete(href: String, etag: String?): Int =
        request("DELETE", href, null, if (etag != null) mapOf("If-Match" to etag) else emptyMap()).status
}
