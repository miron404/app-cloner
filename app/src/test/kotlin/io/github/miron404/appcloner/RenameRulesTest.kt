package io.github.miron404.appcloner

import io.github.miron404.appcloner.clone.ManifestRewriter
import io.github.miron404.appcloner.clone.PackageNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two string rules every rewrite is built out of, and the package-name rules around them. */
class RenameRulesTest {

    @Test
    fun `expands a leading dot against the manifest package`() {
        assertEquals(
            "com.example.app.MainActivity",
            ManifestRewriter.expandClassName(".MainActivity", "com.example.app"),
        )
    }

    @Test
    fun `expands a bare class name`() {
        assertEquals(
            "com.example.app.MainActivity",
            ManifestRewriter.expandClassName("MainActivity", "com.example.app"),
        )
    }

    @Test
    fun `leaves an absolute class name alone`() {
        assertNull(ManifestRewriter.expandClassName("androidx.work.Worker", "com.example.app"))
        assertNull(ManifestRewriter.expandClassName("", "com.example.app"))
    }

    @Test
    fun `swaps the package itself and anything under it`() {
        assertEquals("new.pkg", ManifestRewriter.swapPrefix("old.pkg", "old.pkg", "new.pkg"))
        assertEquals(
            "new.pkg.provider",
            ManifestRewriter.swapPrefix("old.pkg.provider", "old.pkg", "new.pkg"),
        )
    }

    @Test
    fun `does not swap a package that merely shares a prefix`() {
        // 'old.pkgextra' starts with 'old.pkg' as characters but is a different package, and the
        // dot is the only thing that distinguishes them.
        assertNull(ManifestRewriter.swapPrefix("old.pkgextra", "old.pkg", "new.pkg"))
        assertNull(ManifestRewriter.swapPrefix("android.permission.INTERNET", "old.pkg", "new.pkg"))
    }

    @Test
    fun `package names need two letter-led segments`() {
        assertTrue(PackageNames.isValid("com.example.app"))
        assertTrue(PackageNames.isValid("a.b"))
        assertTrue(PackageNames.isValid("com.example.app_2"))
        assertFalse(PackageNames.isValid("single"))
        assertFalse(PackageNames.isValid("com.2example"))
        assertFalse(PackageNames.isValid("com.example-app"))
        assertFalse(PackageNames.isValid("com..example"))
        assertFalse(PackageNames.isValid(""))
    }

    @Test
    fun `suggestion does not stack suffixes when a clone is rebuilt`() {
        assertEquals("com.example.app.clone2", PackageNames.suggest("com.example.app", 2))
        assertEquals(
            "com.example.app.clone2",
            PackageNames.suggest("com.example.app.clone2", 2),
        )
    }
}
