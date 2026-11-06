package io.github.landrop.security

import com.google.gson.Gson
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import io.github.landrop.model.DeviceInfo
import io.github.landrop.protocol.LocalSendProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Actual loopback mTLS, using LocalSend's certificate/JSON identity contract. Not a TV test. */
class TlsPeerClientTest {
    private val clientStore = store("client")
    private val clientIdentity = TlsIdentity(clientStore.getKey("client", PASSWORD) as PrivateKey,
        clientStore.getCertificate("client") as X509Certificate)
    private val serverStore = store("server")
    private val serverCertificate = serverStore.getCertificate("server") as X509Certificate
    private val base = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    @Test fun registrationPresentsCertificateMatchingAdvertisedIdentity() = withServer { server, seen ->
        val identity = DeviceInfo("客厅电视", "2.0", "TV", "headless", clientIdentity.fingerprint, 53317, "http")
        val request = Request.Builder().url(url(server)).post(Gson().toJson(identity).toByteArray(Charsets.UTF_8).toRequestBody()).build()
        TlsPeerClient(clientIdentity, base).client("https", certificateFingerprint(serverCertificate))
            .newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals(certificateFingerprint(serverCertificate), response.body!!.string())
            }
        assertEquals(identity.fingerprint, seen.get()?.fingerprint)
        assertEquals("客厅电视", seen.get()?.alias)
    }

    @Test fun wrongServerFingerprintIsRejectedBeforeRegistration() = withServer { server, seen ->
        val request = Request.Builder().url(url(server)).post("{}".toRequestBody()).build()
        try {
            TlsPeerClient(clientIdentity, base).client("https", "0".repeat(64)).newCall(request).execute().close()
            fail("A mismatched certificate must be rejected")
        } catch (_: IOException) { }
        assertNull(seen.get())
    }

    @Test fun peerRequiresClientCertificate() = withServer { server, seen ->
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val noIdentity = base.newBuilder().sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
        try {
            noIdentity.newCall(Request.Builder().url(url(server)).post("{}".toRequestBody()).build()).execute().close()
            fail("mTLS must reject a connection without the client identity")
        } catch (_: IOException) { }
        assertNull(seen.get())
    }

    private fun withServer(block: (HttpsServer, AtomicReference<DeviceInfo?>) -> Unit) {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(serverStore, PASSWORD) }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || certificateFingerprint(chain[0]) != clientIdentity.fingerprint) throw CertificateException("Unexpected client")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = object : HttpsConfigurator(ssl) {
            override fun configure(params: HttpsParameters) {
                params.setSSLParameters(ssl.defaultSSLParameters.apply { needClientAuth = true })
            }
        }
        val seen = AtomicReference<DeviceInfo?>()
        server.createContext(LocalSendProtocol.REGISTER_PATH) { exchange ->
            val device = Gson().fromJson(exchange.requestBody.reader(Charsets.UTF_8), DeviceInfo::class.java)
            seen.set(device)
            val body = certificateFingerprint(serverCertificate).toByteArray()
            exchange.sendResponseHeaders(if (device.fingerprint == clientIdentity.fingerprint) 200 else 403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try { block(server, seen) } finally { server.stop(0) }
    }

    private fun url(server: HttpsServer) = "https://127.0.0.1:${server.address.port}${LocalSendProtocol.REGISTER_PATH}"
    private fun store(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        TlsPeerClientTest::class.java.getResourceAsStream("/tls/$name.p12")!!.use { load(it, PASSWORD) }
    }
    companion object { private val PASSWORD = "test-only".toCharArray() }
}
