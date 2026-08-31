package io.github.miron404.appcloner

import io.github.miron404.appcloner.core.DEFAULT_VALIDITY_YEARS
import io.github.miron404.appcloner.core.DistinguishedName
import io.github.miron404.appcloner.core.KeyAlgorithm
import io.github.miron404.appcloner.core.KeyMaterial
import io.github.miron404.appcloner.core.NewIdentityRequest
import io.github.miron404.appcloner.core.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Signature
import java.util.concurrent.TimeUnit

class DistinguishedNameTest {

    @Test
    fun `blank components fall back to Unknown`() {
        val filled = DistinguishedName(commonName = "  ", organization = "Acme").withDefaults()

        assertEquals(UNKNOWN, filled.commonName)
        assertEquals(UNKNOWN, filled.organizationalUnit)
        assertEquals("Acme", filled.organization)
        assertEquals(UNKNOWN, filled.country)
    }

    @Test
    fun `renders components in keytool order`() {
        val rendered = DistinguishedName(
            commonName = "app",
            organizationalUnit = "ou",
            organization = "org",
            locality = "city",
            state = "region",
            country = "NL",
        ).rfc2253()

        assertEquals("CN=app, OU=ou, O=org, L=city, ST=region, C=NL", rendered)
    }

    @Test
    fun `escapes separators that would otherwise split the name`() {
        val rendered = DistinguishedName(commonName = "Acme, Inc.").rfc2253()

        assertTrue(rendered.startsWith("CN=Acme\\, Inc."))
    }
}

class KeyMaterialTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun installProvider() {
            io.github.miron404.appcloner.core.Bc.install()
        }

        private fun request(
            algorithm: KeyAlgorithm,
            years: Int = DEFAULT_VALIDITY_YEARS,
            dn: DistinguishedName = DistinguishedName(),
        ) = NewIdentityRequest(
            label = "test",
            alias = "test",
            dn = dn,
            validityYears = years,
            algorithm = algorithm,
        )
    }

    @Test
    fun `defaults produce a thirty year self-signed certificate`() {
        val material = KeyMaterial.generate(request(KeyAlgorithm.EC_P256))
        val certificate = material.certificate

        certificate.verify(certificate.publicKey)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertTrue(certificate.subjectX500Principal.name.contains("CN=$UNKNOWN"))

        val lifetimeDays = TimeUnit.MILLISECONDS.toDays(
            certificate.notAfter.time - certificate.notBefore.time
        )
        assertEquals(365L * DEFAULT_VALIDITY_YEARS, lifetimeDays)
    }

    @Test
    fun `key pair actually signs`() {
        val material = KeyMaterial.generate(request(KeyAlgorithm.EC_P256))
        val payload = "apk contents".toByteArray()

        val signature = Signature.getInstance(KeyAlgorithm.EC_P256.signatureAlgorithm).run {
            initSign(material.keyPair.private)
            update(payload)
            sign()
        }

        val verified = Signature.getInstance(KeyAlgorithm.EC_P256.signatureAlgorithm).run {
            initVerify(material.certificate.publicKey)
            update(payload)
            verify(signature)
        }
        assertTrue(verified)
    }

    @Test
    fun `pkcs12 round trips through the strong writer`() {
        val material = KeyMaterial.generate(request(KeyAlgorithm.RSA_2048))
        val password = KeyMaterial.randomKeystorePassword()

        val encoded = KeyMaterial.writePkcs12(
            alias = "release",
            privateKey = material.keyPair.private,
            certificate = material.certificate,
            password = password,
        )
        val loaded = KeyMaterial.readPkcs12(encoded, password, "release")

        assertEquals(material.keyPair.private, loaded.privateKey)
        assertEquals(material.certificate, loaded.chain.first())
    }

    @Test
    fun `pkcs12 refuses the wrong password`() {
        val material = KeyMaterial.generate(request(KeyAlgorithm.RSA_2048))
        val encoded = KeyMaterial.writePkcs12(
            alias = "release",
            privateKey = material.keyPair.private,
            certificate = material.certificate,
            password = "correct horse".toCharArray(),
        )

        assertThrows(Exception::class.java) {
            KeyMaterial.readPkcs12(encoded, "battery staple".toCharArray(), "release")
        }
    }

    @Test
    fun `random keystore passwords are unique and long`() {
        val first = String(KeyMaterial.randomKeystorePassword())
        val second = String(KeyMaterial.randomKeystorePassword())

        assertTrue(first.length >= 42)
        assertTrue(first != second)
    }

    @Test
    fun `certificate pem round trips`() {
        val material = KeyMaterial.generate(request(KeyAlgorithm.EC_P256))
        val pem = KeyMaterial.toPem(material.certificate)

        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertEquals(material.certificate, KeyMaterial.fromPem(pem))
    }

    @Test
    fun `fingerprint is a colon separated sha256`() {
        val material = KeyMaterial.generate(request(KeyAlgorithm.EC_P256))
        val fingerprint = KeyMaterial.fingerprintSha256(material.certificate)

        assertEquals(32, fingerprint.split(":").size)
    }
}
