package dk.rpix.noter.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** En server at teste mod: den falske i hukommelsen eller en rigtig Radicale. */
interface TestBackend : AutoCloseable {
    fun client(password: String? = null): DavApi

    /** En klient, der ikke kan nå serveren. */
    fun offlineClient(): DavApi

    /** Gør alle sync-tokens, klienterne har fået, ugyldige. null = kan ikke lade sig gøre. */
    val invalidToken: String
}

/**
 * Scenarierne fra reference/selftest.py: to enheder A og B mod samme samling.
 * Hver test starter med en tom samling og to tomme enheder.
 */
abstract class SyncScenarios {
    abstract fun backend(): TestBackend

    private lateinit var server: TestBackend
    private lateinit var a: NoteStore
    private lateinit var b: NoteStore

    @Before fun setUp() {
        server = backend()
        a = MemoryNoteStore()
        b = MemoryNoteStore()
    }

    @After fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private fun sync(store: NoteStore, client: DavApi = server.client()) = Syncer(store, client).sync()
    private fun titles(store: NoteStore) = store.visible().map { it.title }.sorted()
    private fun NoteStore.titled(t: String) = visible().first { it.title == t }

    private fun assertConverged() {
        sync(a); sync(b); sync(a)
        assertEquals("A og B ender med samme noter", titles(a), titles(b))
        assertEquals(a.visible().map { it.body }.sorted(), b.visible().map { it.body }.sorted())
        assertTrue("ingen ventende ændringer tilbage", a.pending().isEmpty() && b.pending().isEmpty())
    }

    /** A opretter to noter og sender dem; B henter dem. */
    private fun twoNotesOnBoth() {
        a.add("Indkøbsliste", "Mælk\nBrød\nKaffe", listOf("Privat"))
        a.add("Idéer – æøå 😀", "Én linje")
        sync(a)
        sync(b)
    }

    // -- login og net -------------------------------------------------------

    @Test fun wrongPasswordGivesAuthError() {
        try {
            sync(MemoryNoteStore(), server.client(password = "forkert"))
            fail("forventede AuthError")
        } catch (_: AuthError) {
        }
    }

    @Test fun offlineGivesNetworkErrorAndKeepsChanges() {
        twoNotesOnBoth()
        a.add("Skrevet i toget", "ingen dækning")
        try {
            sync(a, server.offlineClient())
            fail("forventede NetworkError")
        } catch (_: NetworkError) {
        }
        assertTrue("note ligger stadig som ventende lokalt", a.pending().any { it.title == "Skrevet i toget" })
        sync(a)
        sync(b)
        assertTrue("noten når B, når nettet er tilbage", "Skrevet i toget" in titles(b))
        assertConverged()
    }

    // -- grundlæggende synk A -> B -----------------------------------------

    @Test fun firstSyncBetweenTwoDevices() {
        a.add("Indkøbsliste", "Mælk\nBrød\nKaffe", listOf("Privat"))
        a.add("Idéer – æøå 😀", "Én linje")
        var r = sync(a)
        assertEquals("A sender 2 nye noter", 2, r.pushed)
        assertTrue("A har ingen ventende ændringer", a.pending().isEmpty())

        r = sync(b)
        assertEquals("B henter 2 noter ved første sync", 2, r.fetched)
        assertEquals("B har samme titler (inkl. æøå/emoji)", titles(a), titles(b))
        val n = b.titled("Indkøbsliste")
        assertEquals("Mælk\nBrød\nKaffe", n.body)
        assertEquals(listOf("Privat"), n.tags)

        r = sync(b)
        assertEquals("ny sync uden ændringer henter intet", 0, r.fetched)
        assertEquals("ny sync uden ændringer sender intet", 0, r.pushed)
        r = sync(a)
        assertEquals("A henter ikke sine egne ændringer igen", 0, r.fetched)
        assertConverged()
    }

    @Test fun editReachesOtherDevice() {
        twoNotesOnBoth()
        val uid = b.titled("Indkøbsliste").uid
        b.edit(uid, body = "Mælk\nBrød\nKaffe\nTe")
        sync(b)
        sync(a)
        assertTrue("redigering på B ses på A", a.byUid(uid)!!.body.endsWith("Te"))
        assertConverged()
    }

    @Test fun deleteReachesOtherDevice() {
        twoNotesOnBoth()
        val victim = a.visible().first { it.title.startsWith("Idéer") }
        a.deleteNote(victim.uid)
        var r = sync(a)
        assertEquals("A sletter på serveren", 1, r.deletedOnServer)
        r = sync(b)
        assertEquals("sletning når B", 1, r.deletedLocally)
        assertFalse(b.visible().any { it.title.startsWith("Idéer") })
        assertConverged()
    }

    @Test fun deleteOfUnsentNoteNeverReachesServer() {
        val n = a.add("Kladde")
        a.deleteNote(n.uid)
        assertNull(a.byUid(n.uid))
        val r = sync(a)
        assertEquals(0, r.pushed)
        assertEquals(0, sync(b).fetched)
    }

    // -- fremmed klient: egenskaber skal overleve ------------------------------

    @Test fun foreignNoteIsReadAndKeepsItsProperties() {
        val other = server.client()
        val href = other.collectionPath + "foreign-1.ics"
        assertEquals(201, other.put(href, IcalTest.FOREIGN, null).status)
        sync(a)
        val f = a.byUid("foreign-1")
        assertNotNull("A henter note skrevet af anden klient", f)
        assertEquals("Fra jtx", f!!.title)
        assertEquals("Hej, verden", f.body)
        assertEquals(listOf("Arbejde", "Privat"), f.tags)

        a.edit("foreign-1", title = "Fra jtx (rettet i vores app)")
        sync(a)
        val serverRaw = other.get(href)!!.raw.replace("\r\n ", "")
        for (needle in listOf(
            "X-JTX-FOO:bar", "CLASS:PUBLIC", "STATUS:FINAL", "CATEGORIES:Arbejde,Privat",
            "PRODID:-//ical4j//jtx Board//EN", "DESCRIPTION;LANGUAGE=da:Hej\\, verden",
        )) {
            assertTrue("serveren har stadig $needle", needle in serverRaw)
        }
        assertTrue("serveren har den nye titel", "Fra jtx (rettet i vores app)" in serverRaw)
        assertConverged()
    }

    @Test fun eventInCollectionIsIgnored() {
        val other = server.client()
        val href = other.collectionPath + "ev-1.ics"
        val status = other.put(href, EVENT, null).status
        assumeTrue("serveren afviste VEVENT ($status)", status < 400)
        a.add("Rigtig note")
        sync(a)
        assertEquals("kun noten er synlig", listOf("Rigtig note"), titles(a))
        val ignored = a.byHref(href)
        assertTrue("VEVENT huskes som ignoreret", ignored != null && ignored.ignored && ignored.uid == "href:$href")
        assertTrue("ignorerede objekter sendes ikke", a.pending().isEmpty())

        sync(b)
        assertEquals(listOf("Rigtig note"), titles(b))
        // Fuld resync må heller ikke hente den igen eller fjerne den
        val token = server.invalidToken
        b.setMeta("sync_token", token)
        val r = sync(b)
        assertEquals("ignoreret objekt hentes ikke igen", 0, r.fetched)
        assertNotNull(b.byHref(href))
        assertConverged()
    }

    // -- konflikter --------------------------------------------------------------

    @Test fun conflictKeepsBothVersions() {
        twoNotesOnBoth()
        val uid = b.titled("Indkøbsliste").uid
        a.edit(uid, body = "VERSION FRA A")
        b.edit(uid, body = "VERSION FRA B")
        sync(a)
        val r = sync(b)
        assertEquals("konflikt registreres på B", 1, r.conflicts)
        assertEquals("B beholder serverens version (A) i originalen", "VERSION FRA A", b.byUid(uid)!!.body)
        val copies = b.visible().filter { "(konflikt)" in it.title }
        assertEquals("B gemmer sin egen tekst som konfliktkopi", 1, copies.size)
        assertEquals("Indkøbsliste (konflikt)", copies[0].title)
        assertEquals("VERSION FRA B", copies[0].body)
        assertEquals(listOf("Privat"), copies[0].tags)
        assertTrue("konfliktkopien er sendt til serveren", b.pending().isEmpty() && copies[0].href != null)
        sync(a)
        assertTrue("A ser konfliktkopien efter næste sync", a.visible().any { "(konflikt)" in it.title })
        assertConverged()
    }

    @Test fun conflictOnUntitledNoteGetsTrimmedTitle() {
        a.add("", "tekst")
        sync(a)
        sync(b)
        val uid = a.visible().single().uid
        a.edit(uid, body = "fra A")
        b.edit(uid, body = "fra B")
        sync(a)
        sync(b)
        assertEquals("(konflikt)", b.visible().first { it.body == "fra B" }.title)
    }

    @Test fun identicalEditsAreNoConflict() {
        twoNotesOnBoth()
        val uid = a.titled("Indkøbsliste").uid
        a.edit(uid, body = "SAMME TEKST")
        b.edit(uid, body = "SAMME TEKST")
        sync(a)
        val r = sync(b)
        assertEquals("identiske redigeringer giver ingen konflikt", 0, r.conflicts)
        assertFalse(b.visible().any { "(konflikt)" in it.title })
        assertConverged()
    }

    @Test fun editWinsOverDelete() {
        twoNotesOnBoth()
        val uid = a.titled("Indkøbsliste").uid
        a.deleteNote(uid)
        b.edit(uid, body = "REDIGERET FØR SLETNING NÅEDE HELT HERHEN")
        sync(a)
        sync(b)
        sync(a)
        val onB = b.byUid(uid)
        assertTrue("redigering vinder over sletning (note genskabt på B)", onB != null && !onB.dirty)
        val onA = a.byUid(uid)
        assertTrue("noten er tilbage på A", onA != null && onA.body.startsWith("REDIGERET"))
        assertConverged()
    }

    @Test fun deleteAgainstChangedServerVersionRestoresNote() {
        twoNotesOnBoth()
        val uid = a.titled("Indkøbsliste").uid
        b.edit(uid, body = "ÆNDRET PÅ B")
        sync(b)
        a.deleteNote(uid) // A har stadig den gamle etag
        Syncer(a, server.client()).push() // kun push, så DELETE rammer den gamle etag (412)
        val n = a.byUid(uid)
        assertTrue("noten er genskabt med serverens indhold", n != null && !n.deleted && !n.dirty)
        assertEquals("ÆNDRET PÅ B", n!!.body)
        assertFalse("ingen konfliktkopi ved sletning", a.visible().any { "(konflikt)" in it.title })
        assertConverged()
    }

    @Test fun editAgainstServerDeletionIsRecreated() {
        twoNotesOnBoth()
        val uid = a.titled("Indkøbsliste").uid
        b.deleteNote(uid)
        sync(b)
        a.edit(uid, body = "STADIG HER")
        Syncer(a, server.client()).push() // PUT med If-Match på et objekt, der ikke findes -> 412 -> GET 404 -> detach
        sync(a)
        sync(b)
        assertEquals("STADIG HER", b.byUid(uid)!!.body)
        assertConverged()
    }

    // -- sync-token ----------------------------------------------------------------

    @Test fun invalidTokenGivesFullResyncThatFindsDeletions() {
        twoNotesOnBoth()
        val gone = a.titled("Indkøbsliste")
        // Slettet på serveren, uden at A hører om det via sit token
        assertTrue(server.client().delete(gone.href!!, null) in setOf(200, 204))
        a.setMeta("sync_token", server.invalidToken)
        sync(a) // må ikke kaste
        assertNull("fuld resync opdager slettet note", a.byUid(gone.uid))
        assertTrue("resten er urørt", a.visible().any { it.title.startsWith("Idéer") })
        assertConverged()
    }

    @Test fun tokenIsOnlySavedAfterSuccessfulPull() {
        twoNotesOnBoth()
        val before = b.getMeta("sync_token")
        a.add("Ny")
        sync(a)
        try {
            Syncer(b, server.offlineClient()).sync()
            fail("forventede NetworkError")
        } catch (_: NetworkError) {
        }
        assertEquals(before, b.getMeta("sync_token"))
    }

    // -- slut: komplet forløb som i selftest.py -----------------------------------

    @Test fun fullSelftestFlow() {
        twoNotesOnBoth()
        val uid = b.titled("Indkøbsliste").uid
        b.edit(uid, body = "Mælk\nBrød\nKaffe\nTe")
        sync(b); sync(a)
        a.deleteNote(a.visible().first { it.title.startsWith("Idéer") }.uid)
        sync(a); sync(b)
        a.edit(uid, body = "VERSION FRA A")
        b.edit(uid, body = "VERSION FRA B")
        sync(a); sync(b)
        a.add("Skrevet i toget", "ingen dækning")
        assertConverged()
        assertEquals(listOf("Indkøbsliste", "Indkøbsliste (konflikt)", "Skrevet i toget"), titles(a))
    }

    companion object {
        val EVENT = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:ev-1\r\n" +
            "DTSTAMP:20260101T100000Z\r\nDTSTART:20260101T100000Z\r\nSUMMARY:Møde\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
    }
}

/** Kører altid: scenarierne mod [FakeDavServer]. */
class MemorySyncTest : SyncScenarios() {
    override fun backend(): TestBackend = object : TestBackend {
        val fake = FakeDavServer()
        override fun client(password: String?) = if (password == null) fake.client() else fake.client(password)
        override fun offlineClient() = fake.client(offline = true)
        override val invalidToken: String get() {
            fake.invalidateTokens()
            return "tok-0-0"
        }
        override fun close() {}
    }
}
