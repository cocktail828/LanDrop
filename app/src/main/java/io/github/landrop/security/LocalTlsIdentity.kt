package io.github.landrop.security

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** Persistent client certificate: LocalSend proves this identity during mTLS registration. */
internal object LocalTlsIdentity {
    @Volatile private var cached: TlsIdentity? = null

    @Synchronized fun load(context: Context): TlsIdentity {
        cached?.let { return it }
        return loadStoredIdentity(context, TLS_ALIAS, LEGACY_ALIAS).also { cached = it }
    }

    /** Keep a usable legacy identity; retain an incompatible key while migrating to the TLS alias. */
    @Suppress("DEPRECATION")
    internal fun loadStoredIdentity(context: Context, alias: String, legacyAlias: String): TlsIdentity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias) && store.containsAlias(legacyAlias)) {
            val legacy = readIdentity(store, legacyAlias)
            if (supportsTlsSigning(legacy.privateKey)) return legacy
        }
        if (!store.containsAlias(alias)) {
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            val spec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    // Conscrypt delegates prehashed PKCS#1 and pre-encoded PSS signatures
                    // through RSA Cipher operations. KeyMint requires DIGEST_NONE for both
                    // and PADDING_NONE for the latter, even though the key only signs.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=LanDrop"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(start)
                    .setCertificateNotAfter(end)
                    .build()
            } else KeyPairGeneratorSpec.Builder(context.applicationContext)
                .setAlias(alias)
                .setSubject(X500Principal("CN=LanDrop"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start)
                .setEndDate(end)
                .setKeySize(2048)
                .build()
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        return readIdentity(store, alias)
    }

    private fun readIdentity(store: KeyStore, alias: String) = TlsIdentity(
        store.getKey(alias, null) as PrivateKey, store.getCertificate(alias) as X509Certificate)

    private fun supportsTlsSigning(key: PrivateKey): Boolean {
        // The pre-M KeyPairGeneratorSpec API does not restrict digests or paddings.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java)
        return KeyProperties.DIGEST_NONE in info.digests &&
            KeyProperties.ENCRYPTION_PADDING_NONE in info.encryptionPaddings &&
            KeyProperties.SIGNATURE_PADDING_RSA_PKCS1 in info.signaturePaddings
    }

    private const val LEGACY_ALIAS = "landrop.localsend.client"
    private const val TLS_ALIAS = "landrop.localsend.client.tls-v2"
}
