package io.github.landrop.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

class LocalTlsIdentityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun keystoreCertificateIsSelfSignedAndCanProveItsIdentity() {
        val identity = LocalTlsIdentity.load(context)
        identity.certificate.verify(identity.certificate.publicKey)
        identity.certificate.checkValidity()
        assertEquals(identity.fingerprint, LocalTlsIdentity.load(context).fingerprint)
        val message = "LocalSend client proof".toByteArray()
        val signed = Signature.getInstance("SHA256withRSA").apply { initSign(identity.privateKey); update(message) }.sign()
        assertTrue(Signature.getInstance("SHA256withRSA").apply {
            initVerify(identity.certificate); update(message)
        }.verify(signed))
    }

    @Test fun conscryptRawRsaOperationsAreAuthorized() {
        assertRawSigning(LocalTlsIdentity.load(context))
    }

    @Test fun incompatibleLegacyKeyMigratesOnceWithoutDeletingTheOldIdentity() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        withAliases { store, alias, legacy ->
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 1) }.time
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(legacy, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=Legacy LanDrop"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(start).setCertificateNotAfter(end).build())
                generateKeyPair()
            }
            val oldCertificate = store.getCertificate(legacy) as X509Certificate
            // Reproduce the exact raw operation rejected by the reported device.
            try {
                Cipher.getInstance("RSA/ECB/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, store.getKey(legacy, null))
                }.doFinal(ByteArray(256).apply { this[255] = 42 })
                fail("Legacy key must reject raw TLS signing")
            } catch (_: java.security.InvalidKeyException) { }
            val migrated = LocalTlsIdentity.loadStoredIdentity(context, alias, legacy)
            assertNotEquals(certificateFingerprint(oldCertificate), migrated.fingerprint)
            assertEquals(oldCertificate, store.getCertificate(legacy))
            assertEquals(migrated.fingerprint, LocalTlsIdentity.loadStoredIdentity(context, alias, legacy).fingerprint)
            assertRawSigning(migrated)
        }
    }

    @Test fun compatibleLegacyIdentityKeepsItsFingerprint() {
        withAliases { store, alias, legacy ->
            val before = LocalTlsIdentity.loadStoredIdentity(context, legacy, "$legacy.absent")
            val after = LocalTlsIdentity.loadStoredIdentity(context, alias, legacy)
            assertEquals(before.fingerprint, after.fingerprint)
            assertFalse(store.containsAlias(alias))
        }
    }

    @Test fun nativeKeystoreCompletesMutualTls12() = assertMutualTls("TLSv1.2")

    @Test fun nativeKeystoreCompletesMutualTls13() = assertMutualTls("TLSv1.3")

    private fun assertRawSigning(identity: TlsIdentity) {
        val block = ByteArray(256).apply { this[255] = 42 }
        for (padding in listOf("PKCS1Padding", "NoPadding")) {
            val message = if (padding == "NoPadding") block else "Prehashed TLS proof".toByteArray()
            val signed = Cipher.getInstance("RSA/ECB/$padding").apply {
                init(Cipher.ENCRYPT_MODE, identity.privateKey)
            }.doFinal(message)
            val recovered = Cipher.getInstance("RSA/ECB/$padding").apply {
                init(Cipher.DECRYPT_MODE, identity.certificate.publicKey)
            }.doFinal(signed)
            assertArrayEquals(padding, message, recovered)
        }
    }

    private fun withAliases(block: (KeyStore, String, String) -> Unit) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "landrop.test.tls.${UUID.randomUUID()}"
        val legacy = "$alias.legacy"
        try { block(store, alias, legacy) }
        finally { store.deleteEntry(alias); store.deleteEntry(legacy) }
    }

    /** Exercise Android's real TLS provider with the same non-exportable key used by the app. */
    private fun assertMutualTls(protocol: String) {
        val identity = LocalTlsIdentity.load(context)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || certificateFingerprint(chain[0]) != identity.fingerprint)
                    throw java.security.cert.CertificateException("Unexpected client certificate")
            }
        }
        val keys = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("identity")
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "identity"
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (keyType == "RSA") arrayOf("identity") else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
                if (keyType == "RSA") "identity" else null
            override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), null) }
        assumeTrue(protocol in ssl.supportedSSLParameters.protocols)
        val server = ssl.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val worker = Executors.newSingleThreadExecutor()
        val base = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val client = TlsPeerClient(identity, base).client("https", identity.fingerprint)
        try {
            server.enabledProtocols = arrayOf(protocol)
            server.needClientAuth = true
            server.soTimeout = 15000
            val received = worker.submit<String> {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10000
                    socket.startHandshake()
                    val peer = socket.session.peerCertificates[0] as X509Certificate
                    val input = socket.inputStream.bufferedReader()
                    while (true) { if (input.readLine().isNullOrEmpty()) break }
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                    socket.outputStream.write(response.toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                    certificateFingerprint(peer)
                }
            }
            client.newCall(Request.Builder().url("https://127.0.0.1:${server.localPort}/test").build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("OK", it.body.string())
                assertEquals(protocol, it.handshake!!.tlsVersion.javaName)
            }
            assertEquals(identity.fingerprint, received.get(15, TimeUnit.SECONDS))
        } finally {
            server.close()
            worker.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
