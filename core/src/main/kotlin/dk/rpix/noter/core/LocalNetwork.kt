package dk.rpix.noter.core

import java.net.Inet6Address
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Ligger serveren på lokalnetværket? Fra Android 17 kræver det tilladelsen ACCESS_LOCAL_NETWORK,
 * ellers ender forbindelsen i en timeout uden at nå serveren (issue #3).
 *
 * Lokalt er private og link-lokale adresser (10/8, 172.16/12, 192.168/16, 169.254/16, fc00::/7,
 * fe80::/10), navne under `.local` og `.home.arpa`, navne uden punktum og navne, der slås op til en
 * lokal adresse. Loopback (127.0.0.1, ::1) er ikke lokalnetværk.
 */
object LocalNetwork {
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isLocal(url: String, resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): Boolean {
        val host = url.trim().toHttpUrlOrNull()?.host?.lowercase()?.trimEnd('.') ?: return false
        if (host.contains(':') || ipv4.matches(host)) {
            // IP-literal: getByName slår ikke op i DNS
            return isLocalAddress(InetAddress.getByName(host))
        }
        if (host == "localhost") return false
        if (host.endsWith(".local") || host.endsWith(".home.arpa") || !host.contains('.')) return true
        val addresses = runCatching { resolve(host) }.getOrNull() ?: return false
        return addresses.any(::isLocalAddress)
    }

    fun isLocalAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress) return false
        if (a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        // Unique local IPv6 (fc00::/7); isSiteLocalAddress dækker kun det forældede fec0::/10
        return a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc
    }
}
