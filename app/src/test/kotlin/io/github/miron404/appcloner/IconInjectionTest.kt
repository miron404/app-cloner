package io.github.miron404.appcloner

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import io.github.miron404.appcloner.clone.IconInjector
import io.github.miron404.appcloner.clone.ManifestRewriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Checks the generated launcher icon lands in the APK as a real resource.
 *
 * This is the part with no aapt2 behind it: the `<adaptive-icon>` document is assembled as binary
 * XML by hand and the mipmap entries are added to `resources.arsc` directly. The test writes the
 * APK out and reads it back, because an in-memory model that looks right proves nothing about what
 * was serialised.
 *
 * The layer images are not real PNGs. Nothing in the resource table inspects them; they are file
 * entries whose bytes only matter to the launcher, and keeping them arbitrary lets this run on a
 * plain JVM where `Bitmap` does not exist.
 */
class IconInjectionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `adds an adaptive icon and points the manifest at it`() {
        val source = findDebugApk()
        assumeTrue("No debug APK built yet", source != null)
        requireNotNull(source)

        val output = File(temporaryFolder.root, "icon.apk")
        var injectedId = 0

        ApkModule.loadApkFile(source).use { module ->
            module.setLoadDefaultFramework(false)
            val manifest = requireNotNull(module.androidManifest)
            injectedId = IconInjector.inject(
                module = module,
                foreground = "foreground".toByteArray(),
                background = "background".toByteArray(),
                monochrome = "monochrome".toByteArray(),
                flattened = "flattened".toByteArray(),
            )
            ManifestRewriter.setIcon(manifest, injectedId)
            module.refreshManifest()
            module.refreshTable()
            module.writeApk(output)
        }
        assertNotEquals("no resource id was allocated", 0, injectedId)

        ApkModule.loadApkFile(output).use { module ->
            module.setLoadDefaultFramework(false)
            val manifest = requireNotNull(module.androidManifest)
            assertEquals(injectedId, manifest.iconResourceId)

            val pkg = requireNotNull(module.tableBlock?.pickOne())
            val entry = requireNotNull(pkg.getResource(injectedId)) {
                "the id in the manifest resolves to nothing"
            }
            assertEquals(IconInjector.NAME, entry.name)

            // One resource id, two configurations: the adaptive XML and a raster fallback.
            val paths = entry.stringValues.asSequence().toList()
            assertTrue(
                "expected an anydpi-v26 xml, got $paths",
                paths.any { it == "res/mipmap-anydpi-v26/${IconInjector.NAME}.xml" },
            )
            assertTrue(
                "expected a raster fallback, got $paths",
                paths.any { it == "res/mipmap-xxxhdpi/${IconInjector.NAME}.png" },
            )

            for (path in paths) {
                assertNotNull("$path is not in the archive", module.zipEntryMap.getInputSource(path))
            }

            // The hand-built binary XML has to survive a round trip through the parser.
            val xml = requireNotNull(
                module.zipEntryMap.getInputSource("res/mipmap-anydpi-v26/${IconInjector.NAME}.xml")
            )
            val document = ResXmlDocument()
            xml.openStream().use { document.readBytes(it) }
            val root = requireNotNull(document.documentElement)
            assertEquals("adaptive-icon", root.name)

            val layers = root.recursive(ResXmlElement::class.java).asSequence()
                .filter { it != root }
                .associate { layer ->
                    val drawable = requireNotNull(
                        layer.searchAttributeByResourceId(android.R.attr.drawable)
                    ) { "<${layer.name}> has no android:drawable" }
                    assertEquals(ValueType.REFERENCE, drawable.valueType)
                    layer.name to drawable.data
                }

            assertEquals(setOf("background", "foreground", "monochrome"), layers.keys)
            for ((name, id) in layers) {
                val layerEntry = requireNotNull(pkg.getResource(id)) { "<$name> references nothing" }
                assertEquals("${IconInjector.NAME}_$name", layerEntry.name)
            }
        }
    }

    private companion object {
        /** Unit tests run with the module directory as their working directory. */
        fun findDebugApk(): File? =
            File("build/outputs/apk/debug")
                .listFiles { file -> file.extension == "apk" }
                ?.firstOrNull()
    }
}
