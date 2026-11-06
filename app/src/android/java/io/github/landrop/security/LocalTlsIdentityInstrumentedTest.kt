package io.github.landrop.security

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature

class LocalTlsIdentityInstrumentedTest {
    @Test fun keystoreCertificateIsSelfSignedAndCanProveItsIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
}
