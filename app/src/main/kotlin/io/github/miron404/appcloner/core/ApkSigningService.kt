package io.github.miron404.appcloner.core

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.apksig.KeyConfig
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.cert.X509Certificate

/** What the APK says about itself, read straight from its binary manifest. */
data class ApkInfo(
    val packageName: String?,
    val minSdkVersion: Int,
    val versionCode: Long,
    val debuggable: Boolean,
)

data class SignOptions(
    val schemes: SignatureSchemes,
    /** Re-align uncompressed entries instead of preserving the input's layout. */
    val realign: Boolean = true,
    val minSdkOverride: Int? = null,
)

data class SignerSummary(
    val subject: String,
    val fingerprintSha256: String,
)

data class VerificationReport(
    val verified: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val v4: Boolean,
    val signers: List<SignerSummary>,
    val errors: List<String>,
    val warnings: List<String>,
)

object ApkSigningService {

    private const val DEFAULT_MIN_SDK = 24

    /** Alignment for uncompressed native libraries; 16 KB is required by recent Android releases. */
    private const val LIBRARY_PAGE_ALIGNMENT = 16384

    fun inspect(apk: File): ApkInfo = RandomAccessFile(apk, "r").use { file ->
        val manifest = ApkUtils.getAndroidManifest(DataSources.asDataSource(file))
        val bytes = ByteArray(manifest.remaining()).also { manifest.get(it) }
        fun buffer() = ByteBuffer.wrap(bytes)
        ApkInfo(
            packageName = runCatching {
                ApkUtils.getPackageNameFromBinaryAndroidManifest(buffer())
            }.getOrNull(),
            minSdkVersion = runCatching {
                ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(buffer())
            }.getOrDefault(DEFAULT_MIN_SDK),
            versionCode = runCatching {
                ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(buffer())
            }.getOrDefault(0L),
            debuggable = runCatching {
                ApkUtils.getDebuggableFromBinaryAndroidManifest(buffer())
            }.getOrDefault(false),
        )
    }

    /**
     * Signs [input] into [output]. The private key is only materialised for the duration of the
     * call and the caller is expected to close the [UnlockedIdentity] afterwards.
     */
    fun sign(
        identity: UnlockedIdentity,
        input: File,
        output: File,
        v4Output: File?,
        options: SignOptions,
    ) {
        require(options.schemes.any) { "Select at least one signature scheme" }

        val material = KeyMaterial.readPkcs12(
            identity.pkcs12,
            identity.keystorePassword,
            identity.meta.alias,
        )
        val minSdk = options.minSdkOverride
            ?: runCatching { inspect(input).minSdkVersion }.getOrDefault(DEFAULT_MIN_SDK)

        val signerConfig = ApkSigner.SignerConfig.Builder(
            identity.meta.alias,
            KeyConfig.Jca(material.privateKey),
            material.chain,
        ).build()

        val builder = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(options.schemes.v1)
            .setV2SigningEnabled(options.schemes.v2)
            .setV3SigningEnabled(options.schemes.v3)
            .setV4SigningEnabled(options.schemes.v4)
            .setAlignmentPreserved(!options.realign)
            .setLibraryPageAlignmentBytes(LIBRARY_PAGE_ALIGNMENT)
            .setCreatedBy("APK Signer")

        if (options.schemes.v4 && v4Output != null) builder.setV4SignatureOutputFile(v4Output)

        builder.build().sign()
    }

    fun verify(apk: File, v4Signature: File?, minSdkOverride: Int? = null): VerificationReport {
        val builder = ApkVerifier.Builder(apk)
        val minSdk = minSdkOverride
            ?: runCatching { inspect(apk).minSdkVersion }.getOrDefault(DEFAULT_MIN_SDK)
        builder.setMinCheckedPlatformVersion(minSdk)
        if (v4Signature != null && v4Signature.isFile) builder.setV4SignatureFile(v4Signature)

        val result = builder.build().verify()
        // getAllErrors folds in per-signer failures; result.errors alone would report a rejected
        // APK with an empty explanation.
        return VerificationReport(
            verified = result.isVerified,
            v1 = result.isVerifiedUsingV1Scheme,
            v2 = result.isVerifiedUsingV2Scheme,
            v3 = result.isVerifiedUsingV3Scheme,
            v4 = result.isVerifiedUsingV4Scheme,
            signers = result.signerCertificates.map { it.summarise() },
            errors = result.allErrors.map { it.toString() },
            warnings = buildList {
                addAll(result.warnings)
                result.v1SchemeSigners.forEach { addAll(it.warnings) }
                result.v2SchemeSigners.forEach { addAll(it.warnings) }
                result.v3SchemeSigners.forEach { addAll(it.warnings) }
                result.v4SchemeSigners.forEach { addAll(it.warnings) }
            }.map { it.toString() },
        )
    }

    private fun X509Certificate.summarise() = SignerSummary(
        subject = subjectX500Principal.name,
        fingerprintSha256 = KeyMaterial.fingerprintSha256(this),
    )
}
