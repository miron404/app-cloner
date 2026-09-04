package io.github.miron404.appcloner

import io.github.miron404.appcloner.clone.BadgeCorner
import io.github.miron404.appcloner.clone.BadgeGeometry
import io.github.miron404.appcloner.clone.CloneRecord
import io.github.miron404.appcloner.clone.IconMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Where the badge is allowed to sit, and what an older record makes of the choice.
 *
 * The badge is drawn on a 108dp layer of which a launcher only ever shows the central 72dp, and the
 * strictest mask in common use — the circular one — keeps a disc rather than a rounded square. A
 * corner that puts the badge outside that disc looks right on the phone it was designed on and gets
 * clipped on the next one, which is the kind of thing nobody notices until the clone is installed.
 * So the arithmetic is checked here rather than by eye.
 */
class BadgeGeometryTest {

    @Test
    fun `every corner keeps the badge inside a circular mask`() {
        for (corner in BadgeCorner.entries) {
            val fromCentre = hypot(
                (corner.x - 0.5f).toDouble(),
                (corner.y - 0.5f).toDouble(),
            ).toFloat()
            val outerEdge = fromCentre + BadgeGeometry.RADIUS
            assertTrue(
                "${corner.label} puts the badge's edge at $outerEdge, outside the " +
                    "${BadgeGeometry.MASK_RADIUS} a circular mask keeps",
                outerEdge < BadgeGeometry.MASK_RADIUS,
            )
        }
    }

    @Test
    fun `the corners are four distinct positions, symmetric about the centre`() {
        assertEquals(4, BadgeCorner.entries.map { it.x to it.y }.toSet().size)
        assertEquals(2, BadgeCorner.entries.count { it.x < 0.5f })
        assertEquals(2, BadgeCorner.entries.count { it.y < 0.5f })
        for (corner in BadgeCorner.entries) {
            assertEquals(BadgeGeometry.OFFSET, abs(corner.x - 0.5f), 1e-6f)
            assertEquals(BadgeGeometry.OFFSET, abs(corner.y - 0.5f), 1e-6f)
        }
    }

    /**
     * A registry written before the corner could be chosen has no field for it.
     *
     * This matters more than it looks: the registry treats a decoding failure as an empty file, so
     * a record that stopped parsing would not raise anything — it would quietly take every clone,
     * and every "update available" with it, off the list.
     */
    @Test
    fun `a record written before the corner existed still decodes`() {
        val json = """
            {
              "id": "abc",
              "source": {
                "packageName": "com.example.app",
                "label": "Example",
                "versionName": "1.0",
                "versionCode": 1,
                "kind": "INSTALLED"
              },
              "clonePackage": "com.example.app.clone2",
              "cloneLabel": "Example 2",
              "identityId": "identity",
              "identityLabel": "Identity",
              "iconMode": "BADGE",
              "badgeText": "2",
              "deepRename": false,
              "renameIntentActions": false,
              "cloneIndex": 2,
              "createdAt": 0,
              "builtAt": 0,
              "builtFromVersionCode": 1,
              "builtFromVersionName": "1.0"
            }
        """.trimIndent()

        val record = Json { ignoreUnknownKeys = true }.decodeFromString<CloneRecord>(json)

        assertEquals(IconMode.BADGE, record.iconMode)
        // Bottom right is where the badge was drawn before it could be moved, so that is what an
        // older clone has to keep on rebuilding as.
        assertEquals(BadgeCorner.BOTTOM_RIGHT, record.badgeCorner)
        assertEquals(BadgeCorner.BOTTOM_RIGHT, record.toRequest().badgeCorner)
    }
}
