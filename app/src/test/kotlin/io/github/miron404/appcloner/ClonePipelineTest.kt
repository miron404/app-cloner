package io.github.miron404.appcloner

import com.reandroid.apk.ApkModule
import io.github.miron404.appcloner.clone.BadgeCorner
import io.github.miron404.appcloner.clone.CloneRequest
import io.github.miron404.appcloner.clone.ClonePipeline
import io.github.miron404.appcloner.clone.DexJob
import io.github.miron404.appcloner.clone.DexOutcome
import io.github.miron404.appcloner.clone.DexWorker
import io.github.miron404.appcloner.clone.IconImages
import io.github.miron404.appcloner.clone.IconInjector
import io.github.miron404.appcloner.clone.IconMode
import io.github.miron404.appcloner.clone.IconRenderer
import io.github.miron404.appcloner.clone.InProcessDexWorker
import io.github.miron404.appcloner.clone.SourceApks
import io.github.miron404.appcloner.clone.SourceInfo
import io.github.miron404.appcloner.clone.SourceKind
import io.github.miron404.appcloner.core.ApkSigningService
import io.github.miron404.appcloner.core.Bc
import io.github.miron404.appcloner.core.SignatureSchemes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    /** Outside [NAMESPACE], so a deep rename from there cannot land the new names under it. */
    private val clonePackage = "io.github.miron404.pipelineclone"

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

    /**
     * The fixture's own package is `….appcloner.debug`, and not one string in its dex files is
     * that or under it: the classes live in the namespace, which the suffix makes a different
     * package. So there is nothing to rewrite, and the indexing pass is enough to know it — which
     * on a phone is the difference between a build and a refusal, because the 42 MB dex alone
     * would have failed the heap check.
     */
    @Test
    fun `a deep rename with nothing to move never starts the worker`() {
        val source = fixture() ?: return
        val worker = FakeDexWorker(heapLimit = 0) { error("nothing should have been sent") }

        val report = build(source, deepRename = true, worker = worker)

        assertFalse("a worker was opened with nothing for it to do", worker.opened)
        val dex = requireNotNull(report.dex) { "a deep rename should still report on the dex" }
        assertTrue("no dex files were seen", dex.dexFiles > 0)
        assertEquals(0, dex.rewrittenStrings)
        val verification = ApkSigningService.verify(report.outputs.single(), null)
        assertTrue(verification.errors.joinToString(" | "), verification.verified)
    }

    /**
     * Renaming from the namespace, as a release build — whose applicationId has no suffix — would
     * be renamed. Its strings are in a few small dex files of the app's own code, and those are
     * the only ones the worker is given; the library dex files, the 42 MB one among them, are not.
     */
    @Test
    fun `deep rename sends only the dex files with something to move, and still verifies`() {
        val source = fixture(packageName = NAMESPACE) ?: return
        val real = InProcessDexWorker()
        val worker = FakeDexWorker(heapLimit = real.heapLimit) { real.rewrite(it) }

        val report = build(source, deepRename = true, worker = worker)

        val dex = requireNotNull(report.dex) { "deep rename produced no report" }
        assertTrue("nothing was rewritten, so nothing was proved", dex.rewrittenStrings > 0)
        assertTrue("no dex was sent to the worker", worker.entries.isNotEmpty())
        assertTrue(
            "every dex was sent, including ones with nothing to move: ${worker.entries}",
            worker.entries.size < dex.dexFiles,
        )
        assertFalse("classes.dex holds only libraries", "classes.dex" in worker.entries)
        assertTrue("the worker was left running", worker.closed)
        val verification = ApkSigningService.verify(report.outputs.single(), null)
        assertTrue(verification.errors.joinToString(" | "), verification.verified)
    }

    /**
     * A deep rename the worker's heap could never hold is refused before anything is sent, and
     * the worker — on a device, a process of its own — is shut down rather than left running.
     */
    @Test
    fun `deep rename is refused up front when the worker's heap is too small`() {
        val source = fixture(packageName = NAMESPACE) ?: return
        // Less than the reserve alone, so no dex at all would fit.
        val worker = FakeDexWorker(heapLimit = 16L * 1024 * 1024) { DexOutcome() }

        val refusal = assertThrows(IllegalStateException::class.java) {
            build(source, deepRename = true, worker = worker)
        }

        assertTrue(refusal.message, refusal.message.orEmpty().contains("deep rename"))
        assertEquals("nothing should have been sent to the worker", 0, worker.entries.size)
        assertTrue("the worker was left running", worker.closed)
    }

    /**
     * The case reported from the phone, now that it happens somewhere it can be survived: the
     * worker runs out, the build fails with a message, and the worker is shut down with it.
     */
    @Test
    fun `a worker that runs out of memory fails the build and is shut down`() {
        val source = fixture(packageName = NAMESPACE) ?: return
        val worker = FakeDexWorker(heapLimit = Long.MAX_VALUE) { DexOutcome(outOfMemory = true) }

        val failure = assertThrows(IllegalStateException::class.java) {
            build(source, deepRename = true, worker = worker)
        }

        assertEquals("the build should stop at the first dex that fails", 1, worker.entries.size)
        assertTrue(failure.message, failure.message.orEmpty().contains("ran out"))
        assertTrue(failure.message, failure.message.orEmpty().contains(worker.entries.single()))
        assertTrue("the worker was left running", worker.closed)
    }

    @Test
    fun `no worker is started when deep rename is off`() {
        val source = fixture(packageName = NAMESPACE) ?: return
        val worker = FakeDexWorker(heapLimit = Long.MAX_VALUE) { DexOutcome() }

        val report = build(source, deepRename = false, worker = worker)

        assertEquals(0, worker.entries.size)
        assertFalse("a worker was opened for a build that had no use for it", worker.opened)
        assertNull(report.dex)
    }

    /** Answers every job through [answer], and remembers which dex entries it was asked about. */
    private class FakeDexWorker(
        override val heapLimit: Long,
        private val answer: suspend (DexJob) -> DexOutcome,
    ) : DexWorker {
        var opened = false
        val entries = mutableListOf<String>()
        var closed = false

        override suspend fun rewrite(job: DexJob): DexOutcome {
            entries += job.entry
            return answer(job)
        }

        override fun close() {
            closed = true
        }
    }

    private val icons = IconRenderer { _, _, _ ->
        IconImages(
            foreground = "fg".toByteArray(),
            background = "bg".toByteArray(),
            monochrome = "mono".toByteArray(),
            flattened = "flat".toByteArray(),
        )
    }

    private fun build(source: SourceApks, deepRename: Boolean, worker: FakeDexWorker? = null) = runBlocking {
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
        // Without a fake, the real work is done in this JVM through the same runner the worker
        // process uses, index file and all.
        val pipeline = ClonePipeline(
            icons = icons,
            openDexWorker = {
                worker?.also { it.opened = true } ?: InProcessDexWorker()
            },
        )
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

    /**
     * The fixture as the pipeline wants it, or null when there is no debug build to use.
     *
     * [packageName] stands in for the package the source is said to have; the pipeline renames
     * from that one, whatever the manifest says.
     */
    private fun fixture(packageName: String? = null): SourceApks? {
        val apk = findDebugApk()
        assumeTrue("No debug APK built yet", apk != null)
        requireNotNull(apk)
        val info = ApkSigningService.inspect(apk)
        return SourceApks(
            info = SourceInfo(
                packageName = packageName ?: requireNotNull(info.packageName),
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
        /** Where the fixture's classes live, and so what its strings are named after. */
        private const val NAMESPACE = "io.github.miron404.appcloner"

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
