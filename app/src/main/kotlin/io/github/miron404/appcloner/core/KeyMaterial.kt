package io.github.miron404.appcloner.core

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS12PfxPdu
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date

/** A generated key pair together with its self-signed certificate. */
class GeneratedKeyMaterial(val keyPair: KeyPair, val certificate: X509Certificate)

/** Private key plus certificate chain read back out of a PKCS#12 blob. */
class LoadedKeyMaterial(val privateKey: PrivateKey, val chain: List<X509Certificate>)

object KeyMaterial {

    /**
     * PBKDF2 rounds for a PKCS#12 that a person has to remember the passphrase for.
     *
     * Sized against offline guessing of a human-chosen passphrase, which is the only thing standing
     * between an exported file and its private key.
     */
    const val EXPORT_KDF_ITERATIONS = 600_000

    /**
     * PBKDF2 rounds for the keystore kept inside the vault.
     *
     * Its password is 256 bits straight from the CSPRNG, so there is no guessing attack for a KDF
     * to slow down; stretching it would only add seconds of CPU to every create, sign and export.
     * What actually protects this blob is the AES-256-GCM envelope and the hardware master key.
     */
    private const val INTERNAL_KDF_ITERATIONS = 10_000

    /**
     * Generates a key pair and its self-signed certificate.
     *
     * Both the key generation and the certificate signature deliberately use the platform's default
     * provider rather than BouncyCastle. On Android that is BoringSSL through Conscrypt, which does
     * the modular arithmetic natively; BouncyCastle would do it in Java `BigInteger` on ART and take
     * minutes for an RSA key. BouncyCastle is still used below for the ASN.1 and PKCS#12 work that
     * the platform does not expose at all.
     */
    fun generate(request: NewIdentityRequest): GeneratedKeyMaterial {
        val algorithm = request.algorithm
        val generator = KeyPairGenerator.getInstance(algorithm.jcaName)
        val curve = algorithm.curveName
        if (curve != null) {
            generator.initialize(ECGenParameterSpec(curve), secureRandom)
        } else {
            generator.initialize(algorithm.keySize, secureRandom)
        }
        val keyPair = generator.generateKeyPair()

        val subject = toX500Name(request.dn.withDefaults())
        // Backdate slightly so a signed APK verifies on devices whose clock lags behind.
        val notBefore = Instant.now().minus(1, ChronoUnit.HOURS)
        val notAfter = notBefore.plus(365L * request.validityYears, ChronoUnit.DAYS)
        val serial = BigInteger(1, randomBytes(16))

        val extensionUtils = JcaX509ExtensionUtils()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            Date.from(notBefore),
            Date.from(notAfter),
            subject,
            keyPair.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extensionUtils.createSubjectKeyIdentifier(keyPair.public),
            )

        val signer = JcaContentSignerBuilder(algorithm.signatureAlgorithm).build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        return GeneratedKeyMaterial(keyPair, certificate)
    }

    /**
     * Serialises a key and certificate as PKCS#12 using PBES2 with AES-256-CBC and HMAC-SHA-256,
     * rather than the 3DES/RC2 defaults that the PKCS#12 keystore SPI still emits.
     */
    fun writePkcs12(
        alias: String,
        privateKey: PrivateKey,
        certificate: X509Certificate,
        password: CharArray,
        iterations: Int = INTERNAL_KDF_ITERATIONS,
    ): ByteArray {
        val provider = Bc.provider
        val friendlyName = DERBMPString(alias)
        val keyId = JcaX509ExtensionUtils().createSubjectKeyIdentifier(certificate.publicKey)
            .keyIdentifier
            .let { DEROctetString(it) }

        fun encryptor() = JcePKCSPBEOutputEncryptorBuilder(NISTObjectIdentifiers.id_aes256_CBC)
            .setProvider(provider)
            .setPRF(AlgorithmIdentifier(PKCSObjectIdentifiers.id_hmacWithSHA256, DERNull.INSTANCE))
            .setIterationCount(iterations)
            .setRandom(secureRandom)
            .build(password)

        val certBag = PKCS12SafeBagBuilder(JcaX509CertificateHolder(certificate))
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, keyId)
            .build()

        val keyBag = PKCS12SafeBagBuilder(
            PrivateKeyInfo.getInstance(privateKey.encoded),
            encryptor(),
        )
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, keyId)
            .build()

        val pfx: PKCS12PfxPdu = PKCS12PfxPduBuilder()
            .addEncryptedData(encryptor(), arrayOf(certBag))
            .addData(keyBag)
            .build(
                JcePKCS12MacCalculatorBuilder(NISTObjectIdentifiers.id_sha256)
                    .setProvider(provider)
                    .setIterationCount(iterations),
                password,
            )

        return pfx.getEncoded(ASN1Encoding.DL)
    }

    fun readPkcs12(pkcs12: ByteArray, password: CharArray, alias: String): LoadedKeyMaterial {
        val store = KeyStore.getInstance("PKCS12", Bc.provider)
        ByteArrayInputStream(pkcs12).use { store.load(it, password) }
        val entryAlias = store.aliases().toList().firstOrNull { it.equals(alias, ignoreCase = true) }
            ?: store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
            ?: throw IllegalStateException("Keystore contains no key entry")
        val privateKey = store.getKey(entryAlias, password) as? PrivateKey
            ?: throw IllegalStateException("Keystore entry is not a private key")
        val chain = store.getCertificateChain(entryAlias)
            ?.filterIsInstance<X509Certificate>()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Keystore entry has no certificate")
        return LoadedKeyMaterial(privateKey, chain)
    }

    /**
     * Loads and JITs the BouncyCastle PKCS#12 machinery by doing a throwaway round trip.
     *
     * First use of that code costs several seconds of class loading and verification, which is
     * otherwise paid while the user waits for their first key. Call it early and off the critical
     * path; it is idempotent and safe to skip.
     */
    fun warmUp() {
        val material = generate(
            NewIdentityRequest(
                label = "warmup",
                alias = "warmup",
                dn = DistinguishedName(),
                validityYears = 1,
                algorithm = KeyAlgorithm.EC_P256,
            )
        )
        val password = randomKeystorePassword()
        val encoded = writePkcs12(
            "warmup",
            material.keyPair.private,
            material.certificate,
            password,
            iterations = 1,
        )
        readPkcs12(encoded, password, "warmup")
        password.wipe()
        encoded.wipe()
    }

    /** A 256-bit random keystore password, encoded so it survives being handled as text. */
    fun randomKeystorePassword(): CharArray =
        Base64.getEncoder().withoutPadding().encodeToString(randomBytes(32)).toCharArray()

    fun toX500Name(dn: DistinguishedName): X500Name = X500NameBuilder(BCStyle.INSTANCE)
        .addIfPresent(BCStyle.CN, dn.commonName)
        .addIfPresent(BCStyle.OU, dn.organizationalUnit)
        .addIfPresent(BCStyle.O, dn.organization)
        .addIfPresent(BCStyle.L, dn.locality)
        .addIfPresent(BCStyle.ST, dn.state)
        .addIfPresent(BCStyle.C, dn.country)
        .build()

    fun toPem(certificate: X509Certificate): String {
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(certificate.encoded)
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n"
    }

    fun fromPem(pem: String): X509Certificate {
        val der = Base64.getMimeDecoder().decode(
            pem.lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
        )
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    fun fingerprintSha256(certificate: X509Certificate): String =
        sha256(certificate.encoded).toHex(":")

    private fun X500NameBuilder.addIfPresent(oid: ASN1ObjectIdentifier, value: String) = apply {
        if (value.isNotBlank()) addRDN(oid, value)
    }
}
