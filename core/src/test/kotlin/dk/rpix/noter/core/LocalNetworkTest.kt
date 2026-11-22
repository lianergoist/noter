package dk.rpix.noter.core

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    /** Ingen rigtig DNS i testen: kun de navne, testen selv kender. */
    private fun resolver(vararg map: Pair<String, String>): (String) -> List<InetAddress> = { host ->
        map.filter { it.first == host }.map { InetAddress.getByName(it.second) }
            .ifEmpty { throw UnknownHostException(host) }
    }

    private val none = resolver()

    private fun local(url: String, resolve: (String) -> List<InetAddress> = none) = LocalNetwork.isLocal(url, resolve)

    @Test fun privateIpv4() {
        assertTrue(local("https://192.168.2.120/dav.php/calendars/Keli/default/"))
        assertTrue(local("https://10.0.0.5:8443/anna/noter/"))
        assertTrue(local("https://172.16.1.1/"))
        assertTrue(local("https://172.31.255.255/"))
        assertTrue(local("https://169.254.10.1/"))
    }

    @Test fun publicIpv4() {
        assertFalse(local("https://172.32.0.1/"))
        assertFalse(local("https://8.8.8.8/"))
        assertFalse(local("https://100.64.0.1/"))
    }

    @Test fun loopbackIsNotLocalNetwork() {
        assertFalse(local("https://127.0.0.1:5232/"))
        assertFalse(local("https://localhost:5232/", resolver("localhost" to "127.0.0.1")))
    }

    @Test fun ipv6() {
        assertTrue(local("https://[fd12:3456::1]/"))
        assertTrue(local("https://[fe80::1]/"))
        assertFalse(local("https://[2001:db8::1]/"))
        assertFalse(local("https://[::1]/"))
    }

    @Test fun localNames() {
        assertTrue(local("https://falco.local/dav.php/"))
        assertTrue(local("https://FALCO.LOCAL./dav.php/"))
        assertTrue(local("https://nas.home.arpa/"))
        assertTrue(local("https://nas/"))
    }

    @Test fun namesResolvingToPrivateAddresses() {
        assertTrue(local("https://nas.example.com/", resolver("nas.example.com" to "192.168.1.2")))
        assertFalse(local("https://radicale.example.com/", resolver("radicale.example.com" to "93.184.216.34")))
    }

    @Test fun unknownPublicNameIsNotLocal() {
        assertFalse(local("https://unknown.example.com/"))
    }

    @Test fun invalidUrl() {
        assertFalse(local("not a url"))
        assertFalse(local(""))
    }
}
