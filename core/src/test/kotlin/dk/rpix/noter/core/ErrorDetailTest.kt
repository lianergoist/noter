package dk.rpix.noter.core

import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #3: den tekniske årsag bag "Kan ikke nå serveren" vises, så brugeren kan fortælle os den. */
class ErrorDetailTest {
    @Test
    fun wrapperIsSkippedAndChainIsShown() {
        val root = ConnectException("Connection refused")
        val mid = SocketTimeoutException("failed to connect to /192.168.2.120 (port 443)").initCause(root)
        val e = NetworkError("failed to connect", mid)
        assertEquals(
            "SocketTimeoutException: failed to connect to /192.168.2.120 (port 443)\n" +
                "← ConnectException: Connection refused",
            technicalDetail(e),
        )
    }

    @Test
    fun repeatedMessagesAreNotRepeated() {
        val root = ConnectException("boom")
        val e = NetworkError("boom", java.io.IOException("boom", root))
        assertEquals("IOException: boom\n← ConnectException", technicalDetail(e))
    }

    @Test
    fun withoutCauseTheErrorItselfIsShown() {
        assertEquals("DavError: HTTP 500: x", technicalDetail(DavError(500, "x")))
        assertNull(technicalDetail(null))
    }

    @Test
    fun refusedConnectionGivesNetworkErrorWithDetail() {
        // En ledig port, der lukkes igen, så forbindelsen afvises
        val port = ServerSocket(0).use { it.localPort }
        val client = DavClient("http://127.0.0.1:$port/anna/noter/", "anna", "x", allowInsecure = true)
        val e = runCatching { client.collectionExists() }.exceptionOrNull()
        assertTrue(e is NetworkError)
        val detail = technicalDetail(e)!!
        assertTrue(detail, detail.contains("ConnectException"))
    }
}
