package io.github.landrop.security

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

internal data class TlsIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String get() = certificateFingerprint(certificate)
}

internal fun certificateFingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }

/** LocalSend uses self-signed certificates pinned by DER SHA-256, rather than DNS names/CAs. */
internal class TlsPeerClient(private val identity: TlsIdentity, private val base: OkHttpClient) {
    private val peers = ConcurrentHashMap<String, OkHttpClient>()

    fun client(protocol: String, fingerprint: String? = null): OkHttpClient {
        require(protocol == "http" || protocol == "https") { "Unsupported LocalSend protocol" }
        if (protocol == "http") return base
        // A null pin is used only for discovery. The caller must match the returned JSON
        // identity against the actual handshake certificate before publishing that device.
        val pin = fingerprint?.uppercase()?.also { require(it.matches(Regex("[0-9A-F]{64}"))) }
        val key = pin ?: "discovery"
        peers[key]?.let { return it }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || pin != null && certificateFingerprint(chain[0]) != pin) {
                    throw CertificateException("LocalSend certificate fingerprint mismatch")
                }
            }
        }
        val keys = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType == "RSA") arrayOf("client") else null
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
                if (keyTypes?.any { it == "RSA" } == true) "client" else null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), null) }
        val result = base.newBuilder().sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, session ->
                // IP addresses and LocalSend aliases are not certificate DNS identities.
                val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
                certificate != null && (pin == null || certificateFingerprint(certificate) == pin)
            }.build()
        if (peers.size > 64) peers.clear()
        return peers.putIfAbsent(key, result) ?: result
    }
}
