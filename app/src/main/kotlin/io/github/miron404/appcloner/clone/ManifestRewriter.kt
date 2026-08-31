package io.github.miron404.appcloner.clone

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType

/**
 * Rewrites a binary `AndroidManifest.xml` so the APK becomes a different application.
 *
 * `ApkModule.setPackageName` alone is not enough. It changes the `package` attribute and the name
 * of the resource table's package, and stops there — which leaves an APK that either refuses to
 * install or installs and cannot start:
 *
 *  - `android:name=".MainActivity"` resolves relative to the manifest package. Renaming the
 *    package without expanding these first points every component at a class that is not there.
 *  - `<provider android:authorities>` is a device-global namespace. A clone that keeps the
 *    original's authority is rejected with `INSTALL_FAILED_CONFLICTING_PROVIDER`.
 *  - A `<permission>` the app declares is likewise global, and a duplicate is rejected with
 *    `INSTALL_FAILED_DUPLICATE_PERMISSION`.
 *
 * Attribute identities come from `android.R.attr`, so they are resolved by the compiler against
 * the platform rather than transcribed as hex constants. Names are only consulted when a manifest
 * was built without resource ids on its attributes.
 */
object ManifestRewriter {

    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

    /** Elements whose `android:name` is a class, and therefore relative to the package. */
    private val CLASS_TAGS = setOf(
        "application", "activity", "activity-alias", "service", "receiver", "provider",
        "instrumentation",
    )

    /** Elements whose `android:name` is a permission, and therefore device-global. */
    private val PERMISSION_TAGS = setOf(
        "permission", "permission-group", "permission-tree",
        "uses-permission", "uses-permission-sdk-23",
    )

    /** Attributes that hold a class name and need expanding before the package moves. */
    private val CLASS_ATTRIBUTES = mapOf(
        android.R.attr.name to "name",
        android.R.attr.targetActivity to "targetActivity",
        android.R.attr.backupAgent to "backupAgent",
        android.R.attr.appComponentFactory to "appComponentFactory",
    )

    /**
     * Attributes that hold a package-derived identifier and need the prefix swapped.
     *
     * `taskAffinity` is included so a clone gets its own task stack; left alone it would name the
     * original's, and the two apps would share a recents entry.
     */
    private val PREFIXED_ATTRIBUTES = mapOf(
        android.R.attr.permission to "permission",
        android.R.attr.readPermission to "readPermission",
        android.R.attr.writePermission to "writePermission",
        android.R.attr.targetPackage to "targetPackage",
        android.R.attr.taskAffinity to "taskAffinity",
    )

    /**
     * Applies [request] to one APK of the source app.
     *
     * [label] is null for every split: only the base APK carries an application label, and adding
     * one to a split makes the manifests disagree.
     */
    fun rewrite(
        module: ApkModule,
        oldPackage: String,
        newPackage: String,
        label: String?,
        renameIntentActions: Boolean,
    ): ManifestRewriteReport {
        val manifest = module.androidManifest
            ?: throw IllegalArgumentException("APK has no AndroidManifest.xml")

        var expanded = 0
        var authorities = 0
        var permissions = 0
        var processes = 0
        var actions = 0

        val elements = manifest.recursive(ResXmlElement::class.java).asSequence().toList()

        // First pass, while the package attribute still says what relative names resolve against.
        for (element in elements) {
            val tag = element.name ?: continue
            for (attribute in element.getAttributes().asSequence().toList()) {
                val key = attribute.stringKey() ?: continue
                val value = attribute.stringValue() ?: continue
                val isClassName = when (key) {
                    "name" -> tag in CLASS_TAGS
                    "targetActivity", "backupAgent", "appComponentFactory" -> true
                    else -> false
                }
                if (!isClassName) continue
                expandClassName(value, oldPackage)?.let {
                    attribute.setValueAsString(it)
                    expanded++
                }
            }
        }

        // Second pass: everything that carries the package name as a prefix.
        for (element in elements) {
            val tag = element.name ?: continue
            for (attribute in element.getAttributes().asSequence().toList()) {
                val key = attribute.stringKey() ?: continue
                val value = attribute.stringValue() ?: continue
                when {
                    key == "authorities" -> {
                        // A provider may declare several authorities, separated by semicolons.
                        val parts = value.split(';')
                        val moved = parts.map { part ->
                            swapPrefix(part.trim(), oldPackage, newPackage) ?: part
                        }
                        if (moved != parts) {
                            attribute.setValueAsString(moved.joinToString(";"))
                            authorities++
                        }
                    }

                    key == "name" && tag in PERMISSION_TAGS ->
                        swapPrefix(value, oldPackage, newPackage)?.let {
                            attribute.setValueAsString(it)
                            permissions++
                        }

                    key == "process" -> {
                        // A name starting with ':' is private to the app and already scoped by it.
                        if (!value.startsWith(":")) {
                            swapPrefix(value, oldPackage, newPackage)?.let {
                                attribute.setValueAsString(it)
                                processes++
                            }
                        }
                    }

                    key in PREFIXED_ATTRIBUTES.values ->
                        swapPrefix(value, oldPackage, newPackage)?.let {
                            attribute.setValueAsString(it)
                        }

                    // Renaming the app's own broadcast actions and categories stops the clone and
                    // the original answering each other's implicit intents. It only holds together
                    // when the code that sends them is rewritten too, so it follows deep rename.
                    renameIntentActions && key == "name" && (tag == "action" || tag == "category") ->
                        swapPrefix(value, oldPackage, newPackage)?.let {
                            attribute.setValueAsString(it)
                            actions++
                        }
                }
            }
        }

        // A shared user id cannot survive a change of signing key, and the install would be
        // rejected outright. Dropping it isolates the clone, which is what a clone wants anyway.
        val manifestElement = manifest.documentElement
        val sharedUserId = manifestElement
            ?.searchAttributeByResourceId(android.R.attr.sharedUserId)
            ?.stringValue()
        if (sharedUserId != null) {
            manifestElement.removeAttributesWithId(android.R.attr.sharedUserId)
            manifestElement.removeAttributesWithId(android.R.attr.sharedUserLabel)
        }

        // Renames the manifest package and the resource table's package name together, which keeps
        // Resources.getIdentifier(name, type, getPackageName()) resolving inside the clone.
        module.setPackageName(newPackage)

        var launchers = 0
        if (label != null) {
            manifest.setApplicationLabel(label)
            // An activity with its own label is what the launcher shows, so the application label
            // alone would leave the clone indistinguishable in the app drawer.
            for (activity in launcherActivities(manifest)) {
                launchers++
                if (activity.searchAttributeByResourceId(android.R.attr.label) != null) {
                    activity.getOrCreateAndroidAttribute("label", android.R.attr.label)
                        .setValueAsString(label)
                }
            }
        }

        return ManifestRewriteReport(
            expandedClassNames = expanded,
            renamedAuthorities = authorities,
            renamedPermissions = permissions,
            renamedProcesses = processes,
            renamedActions = actions,
            strippedSharedUserId = sharedUserId,
            launcherActivities = launchers,
        )
    }

    /**
     * Points the application, and any launcher activity that overrides it, at [resourceId].
     *
     * An activity that declares its own `android:icon` wins over the application's, so leaving it
     * alone would show the source app's unbadged icon in the launcher.
     */
    fun setIcon(manifest: AndroidManifestBlock, resourceId: Int) {
        manifest.iconResourceId = resourceId
        if (manifest.roundIconResourceId != 0) {
            manifest.roundIconResourceId = resourceId
        }
        for (activity in launcherActivities(manifest)) {
            for (attr in listOf(android.R.attr.icon, android.R.attr.roundIcon)) {
                if (activity.searchAttributeByResourceId(attr) != null) {
                    activity.getOrCreateAndroidAttribute(
                        if (attr == android.R.attr.icon) "icon" else "roundIcon",
                        attr,
                    ).setTypeAndData(ValueType.REFERENCE, resourceId)
                }
            }
        }
    }

    /** Activities and aliases the launcher will offer, i.e. MAIN + LAUNCHER in one filter. */
    fun launcherActivities(manifest: AndroidManifestBlock): List<ResXmlElement> =
        manifest.recursive(ResXmlElement::class.java).asSequence()
            .filter { it.name == "activity" || it.name == "activity-alias" }
            .filter { activity ->
                activity.recursive(ResXmlElement::class.java).asSequence()
                    .filter { it.name == "intent-filter" }
                    .any { filter ->
                        filter.hasChild("action", ACTION_MAIN) &&
                            filter.hasChild("category", CATEGORY_LAUNCHER)
                    }
            }
            .toList()

    /** Resolves a class name written relative to the manifest package. Null when already absolute. */
    fun expandClassName(value: String, packageName: String): String? = when {
        value.startsWith(".") -> packageName + value
        value.isNotEmpty() && !value.contains('.') -> "$packageName.$value"
        else -> null
    }

    /** Swaps [old] for [new] when [value] is that package or lives under it. Null when unrelated. */
    fun swapPrefix(value: String, old: String, new: String): String? = when {
        value == old -> new
        value.startsWith("$old.") -> new + value.substring(old.length)
        else -> null
    }

    private fun ResXmlElement.hasChild(tag: String, name: String): Boolean =
        recursive(ResXmlElement::class.java).asSequence().any { child ->
            child.name == tag &&
                child.searchAttributeByResourceId(android.R.attr.name)?.stringValue() == name
        }

    private fun ResXmlAttribute.stringValue(): String? =
        if (valueType == ValueType.STRING) valueAsString else null

    /**
     * The attribute's platform name.
     *
     * Compiled manifests normally keep attribute names alongside their resource ids, but a
     * manifest processed by a shrinker may not, so the id is preferred and the name is the
     * fallback. Attributes with neither an id nor a recognised name are not ours to touch.
     */
    private fun ResXmlAttribute.stringKey(): String? {
        val id = nameId
        if (id != 0) {
            CLASS_ATTRIBUTES[id]?.let { return it }
            PREFIXED_ATTRIBUTES[id]?.let { return it }
            return when (id) {
                android.R.attr.authorities -> "authorities"
                android.R.attr.process -> "process"
                else -> null
            }
        }
        return name?.takeIf { it.isNotBlank() }
    }
}
