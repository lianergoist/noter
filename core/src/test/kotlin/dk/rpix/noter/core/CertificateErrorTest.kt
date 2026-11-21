package dk.rpix.noter.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Issue #3: et certifikat, telefonen ikke stoler på, skal give [CertificateError] og ikke
 * [NetworkError] ("Kan ikke nå serveren"), så brugeren får at vide, hvad der er galt.
 */
class CertificateErrorTest {
    private val dir: File = Files.createTempDirectory("davnotes-cert").toFile()
    private val keystore = File(dir, "server.p12")
    private val pass = "hemmelig".toCharArray()

    init {
        // Selvsigneret certifikat for et andet navn end 127.0.0.1 (keytool følger med JDK'en)
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-keystore", keystore.path, "-storetype", "PKCS12",
            "-storepass", String(pass), "-keypass", String(pass), "-alias", "server",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=falvo.local", "-ext", "SAN=dns:falvo.local",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "keytool fejlede: $out" }
    }

    private val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, pass) } }

    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
        httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) })
        createContext("/") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        start()
    }

    private val url get() = "https://127.0.0.1:${server.address.port}/anna/noter/"

    @After fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    @Test fun untrustedCertificateIsCertificateError() {
        val client = DavClient(url, "anna", "hemmelig", timeoutSeconds = 5)
        assertThrows(CertificateError::class.java) { client.collectionExists() }
    }

    @Test fun wrongHostnameIsCertificateError() {
        // Certifikatet er godkendt (som et installeret rodcertifikat), men gælder et andet navn
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val tm = tmf.trustManagers.first() as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val base = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm).build()
        val client = DavClient(url, "anna", "hemmelig", timeoutSeconds = 5, baseClient = base)
        assertThrows(CertificateError::class.java) { client.collectionExists() }
    }
}
