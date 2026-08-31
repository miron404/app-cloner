package io.github.miron404.appcloner.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Value used for any distinguished-name field the user left blank, mirroring `keytool`. */
const val UNKNOWN = "Unknown"

/** Default certificate validity when the user leaves the field blank. */
const val DEFAULT_VALIDITY_YEARS = 30

@Serializable
data class DistinguishedName(
    @SerialName("cn") val commonName: String = "",
    @SerialName("ou") val organizationalUnit: String = "",
    @SerialName("o") val organization: String = "",
    @SerialName("l") val locality: String = "",
    @SerialName("st") val state: String = "",
    @SerialName("c") val country: String = "",
) {
    /** Replaces every blank component with [UNKNOWN]; the result is what actually gets certified. */
    fun withDefaults(): DistinguishedName = DistinguishedName(
        commonName = commonName.orUnknown(),
        organizationalUnit = organizationalUnit.orUnknown(),
        organization = organization.orUnknown(),
        locality = locality.orUnknown(),
        state = state.orUnknown(),
        country = country.orUnknown(),
    )

    fun rfc2253(): String = listOf(
        "CN" to commonName,
        "OU" to organizationalUnit,
        "O" to organization,
        "L" to locality,
        "ST" to state,
        "C" to country,
    ).joinToString(", ") { (key, value) -> key + "=" + escapeRdn(value) }

    private companion object {
        fun String.orUnknown(): String = trim().ifEmpty { UNKNOWN }

        private val ESCAPED = charArrayOf('\\', ',', '+', '"', '<', '>', ';', '=')

        fun escapeRdn(value: String): String = buildString {
            for (ch in value) {
                if (ch in ESCAPED) append('\\')
                append(ch)
            }
        }
    }
}

enum class KeyAlgorithm(val label: String, val jcaName: String, val signatureAlgorithm: String) {
    RSA_2048("RSA 2048", "RSA", "SHA256withRSA"),
    RSA_3072("RSA 3072", "RSA", "SHA256withRSA"),
    RSA_4096("RSA 4096", "RSA", "SHA512withRSA"),
    EC_P256("ECDSA P-256", "EC", "SHA256withECDSA"),
    EC_P384("ECDSA P-384", "EC", "SHA384withECDSA");

    val keySize: Int
        get() = when (this) {
            RSA_2048 -> 2048
            RSA_3072 -> 3072
            RSA_4096 -> 4096
            EC_P256 -> 256
            EC_P384 -> 384
        }

    val curveName: String?
        get() = when (this) {
            EC_P256 -> "secp256r1"
            EC_P384 -> "secp384r1"
            else -> null
        }

    companion object {
        val DEFAULT = RSA_2048
    }
}

/** What the user fills in on the "new signing identity" form. */
data class NewIdentityRequest(
    val label: String,
    val alias: String,
    val dn: DistinguishedName,
    val validityYears: Int,
    val algorithm: KeyAlgorithm,
)

/**
 * Non-secret description of a stored identity. Kept in cleartext so the list screen works without
 * authentication: everything here is also embedded in every APK the identity signs.
 */
@Serializable
data class IdentityMeta(
    val id: String,
    val label: String,
    val alias: String,
    val dn: DistinguishedName,
    val algorithm: String,
    val keySize: Int,
    val signatureAlgorithm: String,
    val serialNumberHex: String,
    val createdAt: Long,
    val notBefore: Long,
    val notAfter: Long,
    val certificatePem: String,
    val fingerprintSha256: String,
)

/** Secrets for one identity. Only ever exists in memory, and only inside an authenticated scope. */
class UnlockedIdentity(
    val meta: IdentityMeta,
    val keystorePassword: CharArray,
    val pkcs12: ByteArray,
) : AutoCloseable {
    override fun close() {
        keystorePassword.fill(' ')
        pkcs12.fill(0)
    }
}
