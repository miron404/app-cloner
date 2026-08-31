package io.github.miron404.appcloner

import io.github.miron404.appcloner.core.DistinguishedName
import io.github.miron404.appcloner.core.IdentityMeta
import io.github.miron404.appcloner.core.KeyAlgorithm
import io.github.miron404.appcloner.core.KeyMaterial
import io.github.miron404.appcloner.core.NewIdentityRequest
import io.github.miron404.appcloner.core.UnlockedIdentity
import java.util.UUID

/** A throwaway signing identity, generated in memory for a test that needs something to sign with. */
fun testIdentity(alias: String, algorithm: KeyAlgorithm = KeyAlgorithm.RSA_2048): UnlockedIdentity {
    val material = KeyMaterial.generate(
        NewIdentityRequest(
            label = alias,
            alias = alias,
            dn = DistinguishedName(commonName = alias),
            validityYears = 30,
            algorithm = algorithm,
        )
    )
    val password = KeyMaterial.randomKeystorePassword()
    val pkcs12 = KeyMaterial.writePkcs12(
        alias,
        material.keyPair.private,
        material.certificate,
        password,
    )
    val meta = IdentityMeta(
        id = UUID.randomUUID().toString(),
        label = alias,
        alias = alias,
        dn = DistinguishedName(commonName = alias).withDefaults(),
        algorithm = algorithm.jcaName,
        keySize = algorithm.keySize,
        signatureAlgorithm = algorithm.signatureAlgorithm,
        serialNumberHex = material.certificate.serialNumber.toString(16),
        createdAt = 0,
        notBefore = material.certificate.notBefore.time,
        notAfter = material.certificate.notAfter.time,
        certificatePem = KeyMaterial.toPem(material.certificate),
        fingerprintSha256 = KeyMaterial.fingerprintSha256(material.certificate),
    )
    return UnlockedIdentity(meta, password, pkcs12)
}
