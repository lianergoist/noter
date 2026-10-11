package dk.rpix.noter.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Kald, som kun indstillingerne bruger. Springes over uden NOTES_TEST_URL. */
class RadicaleDavClientTest {
    private lateinit var backend: RadicaleBackend
    private lateinit var client: DavClient

    @Before fun setUp() {
        val base = System.getenv("NOTES_TEST_URL")
        assumeTrue("NOTES_TEST_URL er ikke sat", !base.isNullOrBlank())
        backend = RadicaleBackend(base!!)
        client = backend.client() as DavClient
    }

    @After fun tearDown() {
        if (::backend.isInitialized) backend.close()
    }

    @Test fun collectionExistsAfterMkcalendar() {
        assertTrue(client.collectionExists())
        val missing = DavClient(client.collectionUrl.toString().trimEnd('/') + "-findes-ikke/", "anna",
            System.getenv("NOTES_TEST_PASSWORD") ?: "hemmelig", allowInsecure = true)
        assertFalse(missing.collectionExists())
    }

    @Test fun discoverFindsJournalCollection() {
        val found = client.discover()
        val mine = found.single { it.path == client.collectionPath }
        assertEquals("Noter (test)", mine.name)
        assertTrue(mine.onlyJournal)
    }

    @Test fun hrefWithSpecialCharactersRoundTrips() {
        val href = client.collectionPath + "æ ø+å 😀.ics"
        val raw = Ical.newIcs("special-1", "Særlig sti")
        assertEquals(201, client.put(href, raw, null).status)
        val changes = client.syncCollection(null).changes
        assertTrue(changes.any { it.href == href && it.etag != null })
        assertEquals("Særlig sti", Ical.parseIcs(client.multiget(listOf(href)).single().raw).title)
        assertEquals(href, client.get(href)!!.href)
    }
}
