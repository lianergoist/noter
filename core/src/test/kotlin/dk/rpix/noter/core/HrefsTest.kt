package dk.rpix.noter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HrefsTest {
    @Test fun normalizeDecodesPathAndDropsOrigin() {
        assertEquals("/anna/noter/a b.ics", Hrefs.normalize("https://x.dk/anna/noter/a%20b.ics"))
        assertEquals("/anna/noter/æ+ø.ics", Hrefs.normalize("/anna/noter/%C3%A6+%C3%B8.ics"))
        assertEquals("/anna/noter/😀.ics", Hrefs.normalize("/anna/noter/%F0%9F%98%80.ics"))
        assertEquals("/a/100%.ics", Hrefs.normalize("/a/100%.ics"))
    }

    @Test fun encodePathKeepsSlashes() {
        assertEquals("/anna/noter/a%20b%2Bc%C3%A6.ics", Hrefs.encodePath("/anna/noter/a b+cæ.ics"))
        assertEquals("/a/b-c_d.e~f/", Hrefs.encodePath("/a/b-c_d.e~f/"))
    }

    @Test(expected = IllegalArgumentException::class) fun plainHttpIsRejected() {
        DavClient("http://radicale.example/anna/noter/", "anna", "x")
    }

    @Test fun collectionPathIsDecodedWithSlash() {
        val c = DavClient("https://radicale.example/anna/mine%20noter", "anna", "x")
        assertEquals("/anna/mine noter/", c.collectionPath)
    }
}
