package io.github.miron404.appcloner.clone

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import com.reandroid.common.Namespace
import java.util.zip.ZipEntry

/**
 * Adds a generated launcher icon to an APK's resource table and points the manifest at it.
 *
 * New resources are created rather than the app's existing icon being overwritten in place. An
 * icon resource is frequently referenced from somewhere else as well — a notification, an about
 * screen, a shortcut — and replacing its files would change all of those too. A fresh entry only
 * affects whatever is repointed at it, which is the launcher and nothing else.
 *
 * The entry carries two configurations under one resource id: the adaptive icon under
 * `anydpi-v26`, which is what any device this app runs on will pick, and a flattened bitmap under
 * `xxxhdpi` for anything that insists on a raster.
 */
object IconInjector {

    private const val NAME = "ic_clone_launcher"

    /**
     * Configuration qualifiers, written without a leading dash: ResConfig splits on '-', so the
     * directory-style "-anydpi-v26" would begin with an empty token.
     */
    private const val ANYDPI_V26 = "anydpi-v26"
    private const val XXXHDPI = "xxxhdpi"

    /** Writes [layers] into [module] and returns the resource id the manifest should point at. */
    fun inject(module: ApkModule, layers: IconLayers): Int {
        val table = module.tableBlock
            ?: throw IllegalStateException("APK has no resource table to add an icon to")
        val pkg = table.pickOne()
            ?: throw IllegalStateException("Resource table declares no package")

        val foreground = addLayer(module, pkg, "foreground", IconFactory.encodePng(layers.foreground))
        val background = addLayer(module, pkg, "background", IconFactory.encodePng(layers.background))
        val monochrome = layers.monochrome
            ?.let { addLayer(module, pkg, "monochrome", IconFactory.encodePng(it)) }
            ?: 0

        val xmlPath = "res/mipmap-anydpi-v26/$NAME.xml"
        val entry = pkg.getOrCreate(ANYDPI_V26, "mipmap", NAME)
        entry.setValueAsString(xmlPath)
        module.addFile(xmlPath, adaptiveIcon(pkg, background, foreground, monochrome))

        val pngPath = "res/mipmap-xxxhdpi/$NAME.png"
        // Same type and name, so this shares the resource id with the entry above and only
        // differs in the configuration it answers for.
        pkg.getOrCreate(XXXHDPI, "mipmap", NAME).setValueAsString(pngPath)
        module.addFile(pngPath, IconFactory.encodePng(IconFactory.flatten(layers)))

        return entry.resourceId
    }

    private fun addLayer(
        module: ApkModule,
        pkg: PackageBlock,
        role: String,
        png: ByteArray,
    ): Int {
        val name = "${NAME}_$role"
        val path = "res/mipmap-xxxhdpi/$name.png"
        val entry = pkg.getOrCreate(XXXHDPI, "mipmap", name)
        entry.setValueAsString(path)
        module.addFile(path, png)
        return entry.resourceId
    }

    /** Builds the binary `<adaptive-icon>` document by hand; there is no aapt2 to compile one. */
    private fun adaptiveIcon(
        pkg: PackageBlock,
        background: Int,
        foreground: Int,
        monochrome: Int,
    ): ByteArray {
        val document = ResXmlDocument()
        document.packageBlock = pkg
        val root = document.newElement("adaptive-icon")
        root.getOrCreateNamespace(Namespace.URI_ANDROID, Namespace.PREFIX_ANDROID)
        root.addLayer("background", background)
        root.addLayer("foreground", foreground)
        // A missing monochrome layer is legal; it just means the launcher cannot theme the icon.
        if (monochrome != 0) root.addLayer("monochrome", monochrome)
        document.refreshFull()
        return document.bytes
    }

    private fun ResXmlElement.addLayer(tag: String, resourceId: Int) {
        newElement(tag)
            .getOrCreateAndroidAttribute("drawable", android.R.attr.drawable)
            .setTypeAndData(ValueType.REFERENCE, resourceId)
    }

    /**
     * Replaces or adds one archive entry. PNGs are deflated rather than stored, matching what
     * aapt2 emits, so nothing downstream has to reason about alignment for them.
     */
    private fun ApkModule.addFile(path: String, bytes: ByteArray) {
        zipEntryMap.remove(path)
        zipEntryMap.add(ByteInputSource(bytes, path).apply { method = ZipEntry.DEFLATED })
    }
}
