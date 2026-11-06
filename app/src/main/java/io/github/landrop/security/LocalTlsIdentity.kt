package io.github.landrop.security

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** Persistent client certificate: LocalSend proves this identity during mTLS registration. */
internal object LocalTlsIdentity {
    @Volatile private var cached: TlsIdentity? = null

    @Suppress("DEPRECATION")
    @Synchronized fun load(context: Context): TlsIdentity {
        cached?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            val spec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=LanDrop"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(start)
                    .setCertificateNotAfter(end)
                    .build()
            } else KeyPairGeneratorSpec.Builder(context.applicationContext)
                .setAlias(ALIAS)
                .setSubject(X500Principal("CN=LanDrop"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start)
                .setEndDate(end)
                .setKeySize(2048)
                .build()
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        return TlsIdentity(store.getKey(ALIAS, null) as PrivateKey,
            store.getCertificate(ALIAS) as X509Certificate).also { cached = it }
    }

    private const val ALIAS = "landrop.localsend.client"
}
