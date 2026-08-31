package io.github.miron404.appcloner

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import com.reandroid.dex.model.DexFile
import com.reandroid.dex.sections.SectionType
import io.github.miron404.appcloner.clone.DexRewriter
import io.github.miron404.appcloner.clone.ManifestRewriter
import io.github.miron404.appcloner.core.ApkSigningService
import io.github.miron404.appcloner.core.SignOptions
import io.github.miron404.appcloner.core.SignatureSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Exercises the rewriting against a real APK.
 *
 * The fixture is this app's own debug build, the same arrangement the signing test uses: the test
 * is skipped until `assembleDebug` has produced one, rather than a binary being committed. What is
 * being checked here is that ARSCLib actually produces an APK the platform's own parser accepts
 * afterwards — a binary manifest that merely looks right is worth nothing.
 */
class RewriteApkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val newPackage = "io.github.miron404.appcloner.testclone"

    @Test
    fun `renames the package in the manifest and the resource table`() {
        val output = rewriteFixture() ?: return

        // The platform's own manifest reader, not ARSCLib's, has to agree.
        assertEquals(newPackage, ApkSigningService.inspect(output).packageName)

        ApkModule.loadApkFile(output).use { module ->
            module.setLoadDefaultFramework(false)
            assertEquals(newPackage, module.androidManifest?.packageName)
            assertEquals(newPackage, module.tableBlock?.pickOne()?.name)
        }
    }

    @Test
    fun `leaves no component name relative to the old package`() {
        val output = rewriteFixture() ?: return

        ApkModule.loadApkFile(output).use { module ->
            module.setLoadDefaultFramework(false)
            val manifest = requireNotNull(module.androidManifest)
            val classTags = setOf("application", "activity", "service", "receiver", "provider")
            val names = manifest.recursive(ResXmlElement::class.java).asSequence()
                .filter { it.name in classTags }
                .mapNotNull { element ->
                    element.searchAttributeByResourceId(android.R.attr.name)
                        ?.takeIf { it.valueType == ValueType.STRING }
                        ?.valueAsString
                }
                .toList()

            assertTrue("no components found; the fixture is not what this test assumes", names.isNotEmpty())
            for (name in names) {
                assertTrue("'$name' is still relative", !name.startsWith("."))
                assertTrue("'$name' has no package at all", name.contains('.'))
            }
        }
    }

    @Test
    fun `a rewritten apk still signs and verifies`() {
        val output = rewriteFixture() ?: return
        val signed = File(temporaryFolder.root, "signed.apk")

        testIdentity("clone").use { identity ->
            ApkSigningService.sign(
                identity = identity,
                input = output,
                output = signed,
                v4Output = null,
                options = SignOptions(
                    schemes = SignatureSchemes(v1 = true, v2 = true, v3 = true, v4 = false),
                ),
            )
        }

        val report = ApkSigningService.verify(signed, null)
        assertTrue(report.errors.joinToString(" | "), report.verified)
        assertEquals(newPackage, ApkSigningService.inspect(signed).packageName)
    }

    @Test
    fun `dex rewriting moves package strings and spares class names`() {
        val source = findDebugApk()
        assumeTrue("No debug APK built yet", source != null)
        requireNotNull(source)

        // The namespace the classes actually live in, which is also what appears in strings.
        val old = "io.github.miron404.appcloner"
        val new = "io.github.miron404.cloned"

        val dexes = ZipFile(source).use { zip ->
            zip.entries().asSequence()
                .filter { DexRewriter.isDexEntry(it.name) }
                .map { zip.getInputStream(it).use { stream -> stream.readBytes() } }
                .toList()
        }
        assertTrue("fixture has no dex files", dexes.isNotEmpty())

        val classNames = dexes.flatMap { DexRewriter.indexClasses(it, old).toList() }.toSet()
        assertTrue("no classes found under $old", classNames.isNotEmpty())
        val index = DexRewriter.ClassIndex(classNames)

        var rewrittenAny = false
        for (dex in dexes) {
            val result = DexRewriter.rewrite(dex, old, new, index)
            val bytes = result.bytes ?: continue
            rewrittenAny = true

            // Re-reading is the real assertion: a dex whose string ids are out of order, or whose
            // offsets were not rebuilt, does not survive being parsed again.
            DexFile.read(bytes).use { reloaded ->
                val strings = reloaded.getItems(SectionType.STRING_ID).asSequence()
                    .mapNotNull { it.string }
                    .toList()
                assertTrue(
                    "a class name was renamed and its class no longer exists",
                    strings.none { it in classNames && it.startsWith("$new.") },
                )
                // Descriptors use slashes, so the classes themselves must be untouched.
                assertTrue(
                    "a type descriptor was rewritten",
                    strings.none { it.startsWith("L" + new.replace('.', '/')) },
                )
                assertTrue(
                    "the original type descriptors should still be there",
                    strings.any { it.startsWith("L" + old.replace('.', '/')) },
                )
            }
        }
        assertTrue("nothing was rewritten, so nothing was proved", rewrittenAny)
    }

    /** Rewrites the fixture into a new package, or returns null when there is no fixture. */
    private fun rewriteFixture(): File? {
        val source = findDebugApk()
        assumeTrue("No debug APK built yet", source != null)
        requireNotNull(source)

        val output = File(temporaryFolder.root, "rewritten.apk")
        if (output.isFile) return output

        ApkModule.loadApkFile(source).use { module ->
            module.setLoadDefaultFramework(false)
            val oldPackage = requireNotNull(module.androidManifest?.packageName)
            assertNotNull(oldPackage)
            ManifestRewriter.rewrite(
                module = module,
                oldPackage = oldPackage,
                newPackage = newPackage,
                label = "Cloned",
                renameIntentActions = false,
            )
            module.refreshManifest()
            module.refreshTable()
            module.writeApk(output)
        }
        return output
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun installProvider() {
            // Signing writes and reads PKCS#12, which needs the full BouncyCastle provider.
            io.github.miron404.appcloner.core.Bc.install()
        }

        /** Unit tests run with the module directory as their working directory. */
        private fun findDebugApk(): File? =
            File("build/outputs/apk/debug")
                .listFiles { file -> file.extension == "apk" }
                ?.firstOrNull()
    }
}
