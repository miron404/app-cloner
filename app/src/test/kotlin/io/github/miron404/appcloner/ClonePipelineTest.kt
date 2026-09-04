package io.github.miron404.appcloner

import com.reandroid.apk.ApkModule
import io.github.miron404.appcloner.clone.BadgeCorner
import io.github.miron404.appcloner.clone.CloneRequest
import io.github.miron404.appcloner.clone.ClonePipeline
import io.github.miron404.appcloner.clone.IconImages
import io.github.miron404.appcloner.clone.IconInjector
import io.github.miron404.appcloner.clone.IconMode
import io.github.miron404.appcloner.clone.SourceApks
import io.github.miron404.appcloner.clone.SourceInfo
import io.github.miron404.appcloner.clone.SourceKind
import io.github.miron404.appcloner.core.ApkSigningService
import io.github.miron404.appcloner.core.Bc
import io.github.miron404.appcloner.core.SignatureSchemes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs a whole build, from source APK to signed and verified output.
 *
 * The pieces have their own tests; this is about the order they run in. Signing before the manifest
 * was refreshed, or injecting the icon after the package moved, would leave every individual test
 * passing and produce an APK that does not install.
 *
 * Icons arrive through [io.github.miron404.appcloner.clone.IconRenderer], so the pipeline needs no
 * `Context` and the images here are arbitrary bytes: what is being checked is that they end up in
 * the archive under a resource the manifest points at, not what they look like.
 */
class ClonePipelineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val clonePackage = "io.github.miron404.appcloner.pipelineclone"

    @Test
    fun `builds a renamed, re-iconed, signed clone`() {
        val source = fixture() ?: return
        val report = build(source, deepRename = false)

        val output = report.outputs.single()
        assertEquals(clonePackage, report.clonePackage)
        assertEquals(clonePackage, ApkSigningService.inspect(output).packageName)

        val verification = ApkSigningService.verify(output, null)
        assertTrue(verification.errors.joinToString(" | "), verification.verified)
        assertEquals(
            report.signerFingerprint,
            verification.signers.single().fingerprintSha256,
        )

        assertTrue("the icon was not reported as badged", report.icon.contains("badged"))
        // The corner the request asked for is what the report names, so a build cannot silently
        // fall back to a default one.
        assertTrue(report.icon, report.icon.contains("top left"))
        ApkModule.loadApkFile(output).use { module ->
            module.setLoadDefaultFramework(false)
            val manifest = requireNotNull(module.androidManifest)
            val pkg = requireNotNull(module.tableBlock?.pickOne())
            assertEquals(clonePackage, pkg.name)
            val icon = requireNotNull(pkg.getResource(manifest.iconResourceId)) {
                "the manifest icon does not resolve in the clone"
            }
            assertEquals(IconInjector.NAME, icon.name)
            assertNotNull(
                module.zipEntryMap.getInputSource("res/mipmap-anydpi-v26/${IconInjector.NAME}.xml")
            )
        }
    }

    @Test
    fun `deep rename reports what it changed and still verifies`() {
        val source = fixture() ?: return
        val report = build(source, deepRename = true)

        val dex = requireNotNull(report.dex) { "deep rename produced no report" }
        assertTrue("no dex files were seen", dex.dexFiles > 0)
        // The fixture's applicationId is the namespace plus '.debug', and the namespace is what
        // appears in string constants, so the applicationId prefix matches little on its own.
        // What matters is that the pass ran and the result is still a valid, signable APK.
        val verification = ApkSigningService.verify(report.outputs.single(), null)
        assertTrue(verification.errors.joinToString(" | "), verification.verified)
    }

    private fun build(source: SourceApks, deepRename: Boolean) = runBlocking {
        val request = CloneRequest(
            label = "Cloned",
            packageName = clonePackage,
            identityId = "unused",
            iconMode = IconMode.BADGE,
            badgeText = "2",
            badgeCorner = BadgeCorner.TOP_LEFT,
            deepRename = deepRename,
            renameIntentActions = false,
            cloneIndex = 2,
        )
        val pipeline = ClonePipeline { _, _, _ ->
            IconImages(
                foreground = "fg".toByteArray(),
                background = "bg".toByteArray(),
                monochrome = "mono".toByteArray(),
                flattened = "flat".toByteArray(),
            )
        }
        testIdentity("pipeline").use { identity ->
            pipeline.build(
                source = source,
                request = request,
                identity = identity,
                schemes = SignatureSchemes(v1 = true, v2 = true, v3 = true, v4 = false),
                outDir = File(temporaryFolder.root, if (deepRename) "deep" else "plain"),
            ) { }
        }
    }

    /** The fixture as the pipeline wants it, or null when there is no debug build to use. */
    private fun fixture(): SourceApks? {
        val apk = findDebugApk()
        assumeTrue("No debug APK built yet", apk != null)
        requireNotNull(apk)
        val info = ApkSigningService.inspect(apk)
        return SourceApks(
            info = SourceInfo(
                packageName = requireNotNull(info.packageName),
                label = "App Cloner",
                versionName = "1.0.0",
                versionCode = info.versionCode,
                kind = SourceKind.FILE,
            ),
            base = apk,
            splits = emptyList(),
            staging = null,
        )
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun installProvider() {
            Bc.install()
        }

        /** Unit tests run with the module directory as their working directory. */
        private fun findDebugApk(): File? =
            File("build/outputs/apk/debug")
                .listFiles { file -> file.extension == "apk" }
                ?.firstOrNull()
    }
}
