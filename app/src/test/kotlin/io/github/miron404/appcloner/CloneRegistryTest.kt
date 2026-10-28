package io.github.miron404.appcloner

import io.github.miron404.appcloner.clone.BadgeCorner
import io.github.miron404.appcloner.clone.CloneRecord
import io.github.miron404.appcloner.clone.CloneRegistry
import io.github.miron404.appcloner.clone.IconMode
import io.github.miron404.appcloner.clone.SourceInfo
import io.github.miron404.appcloner.clone.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloneRegistryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var file: File
    private lateinit var registry: CloneRegistry

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("registry")
        file = File(root, "clones.json")
        registry = CloneRegistry(file)
    }

    private fun record(id: String, label: String, iconMode: IconMode = IconMode.BADGE) = CloneRecord(
        id = id,
        source = SourceInfo(
            packageName = "com.example.app",
            label = "Example",
            versionName = "1.0",
            versionCode = 1,
            kind = SourceKind.INSTALLED,
        ),
        clonePackage = "com.example.app.$id",
        cloneLabel = label,
        identityId = "identity",
        identityLabel = "Identity",
        iconMode = iconMode,
        badgeText = "2",
        badgeCorner = BadgeCorner.BOTTOM_RIGHT,
        deepRename = false,
        renameIntentActions = false,
        cloneIndex = 2,
        createdAt = 0,
        builtAt = 0,
        builtFromVersionCode = 1,
        builtFromVersionName = "1.0",
    )

    private fun preservedCopies(): List<File> =
        root.listFiles()!!.filter { it.name.startsWith("clones.json.${CloneRegistry.UNREADABLE}-") }

    @Test
    fun `records survive a round trip and leave no temporary file`() {
        registry.put(record("a", "Alpha"))
        registry.put(record("b", "Beta"))
        registry.remove("a")

        assertEquals(listOf("b"), CloneRegistry(file).list().map { it.id })
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertTrue(preservedCopies().isEmpty())
    }

    @Test
    fun `a record this version cannot read costs only itself`() {
        registry.put(record("keep", "Kept", iconMode = IconMode.KEEP))
        registry.put(record("bad", "Broken", iconMode = IconMode.BADGE))
        // An enum value a later version might have dropped.
        val original = file.readText().replace("\"BADGE\"", "\"SPARKLE\"")
        file.writeText(original)

        assertEquals(listOf("keep"), registry.list().map { it.id })
        assertEquals(listOf(original), preservedCopies().map { it.readText() })
    }

    @Test
    fun `an unparseable file is kept aside rather than overwritten`() {
        file.writeText("[{\"id\": \"half-writ")

        assertTrue(registry.list().isEmpty())
        registry.put(record("new", "New"))

        assertEquals(listOf("new"), registry.list().map { it.id })
        assertEquals(listOf("[{\"id\": \"half-writ"), preservedCopies().map { it.readText() })
    }

    @Test
    fun `reading the same broken file repeatedly keeps one copy of it`() {
        file.writeText("not json")

        repeat(5) { registry.list() }

        assertEquals(1, preservedCopies().size)
    }
}
