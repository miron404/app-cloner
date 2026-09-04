package io.github.miron404.appcloner.clone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.io.File

/** The three layers of an adaptive icon, rasterised. [monochrome] is absent on unthemed icons. */
class IconLayers(
    val foreground: Bitmap,
    val background: Bitmap,
    val monochrome: Bitmap?,
) {
    fun recycle() {
        foreground.recycle()
        background.recycle()
        monochrome?.recycle()
    }
}

/**
 * Renders the clone's launcher icon.
 *
 * The interesting constraint is Material You. A themed icon is drawn from the `<monochrome>` layer
 * alone, filled with a single colour from the wallpaper palette, so under themed icons every app
 * on the device is the same colour. A badge painted onto the foreground simply is not there, and
 * recolouring the icon achieves nothing either.
 *
 * The only thing that survives the tint is *shape*. So the badge is cut out of the monochrome
 * layer rather than drawn onto it: a transparent ring, a solid disc inside it, and the digit
 * knocked back out of the disc. Tinted, that reads as a clear marker; untinted, the same badge is
 * drawn in colour on the foreground so it looks deliberate either way.
 */
object IconFactory {

    /** 108dp at xxxhdpi, the density an adaptive icon layer is authored at. */
    const val CANVAS = 432

    // Where the badge sits is [BadgeCorner]'s business; the arithmetic behind those numbers, and
    // the proof that all four corners survive a circular mask, are in [BadgeGeometry].
    private const val BADGE_RADIUS = BadgeGeometry.RADIUS
    private const val BADGE_GAP = BadgeGeometry.GAP

    private const val BADGE_FILL = 0xFF2962FF.toInt()
    private const val BADGE_GLYPH = Color.WHITE

    /**
     * Loads the icon an APK declares, without installing it.
     *
     * `getPackageArchiveInfo` parses the manifest but leaves the paths blank, so the resource
     * loader has nothing to open until they are filled in. The splits matter: a density split is
     * where the launcher icon usually lives once an app is delivered from Play.
     */
    fun loadIcon(context: Context, base: File, splits: List<File>): Drawable? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(base.absolutePath, 0) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = base.absolutePath
        app.publicSourceDir = base.absolutePath
        if (splits.isNotEmpty()) {
            val paths = splits.map { it.absolutePath }.toTypedArray()
            app.splitSourceDirs = paths
            app.splitPublicSourceDirs = paths
        }
        return runCatching { app.loadIcon(pm) }.getOrNull()
    }

    /** Rasterises a drawable into the three layers an adaptive icon is built from. */
    fun toLayers(drawable: Drawable): IconLayers {
        if (drawable is AdaptiveIconDrawable) {
            val background = rasterise(drawable.background)
                ?: solid(dominantColour(rasterise(drawable.foreground)))
            return IconLayers(
                foreground = rasterise(drawable.foreground) ?: transparent(),
                background = background,
                // getMonochrome is API 33, which is this app's minimum.
                monochrome = rasterise(drawable.monochrome),
            )
        }

        // A legacy icon is a single bitmap with no safe zone of its own, so it is inset into the
        // area an adaptive mask always keeps and given a background sampled from its own colours.
        val flat = rasteriseInset(drawable)
        return IconLayers(
            foreground = flat,
            background = solid(dominantColour(flat)),
            monochrome = silhouette(flat),
        )
    }

    /**
     * Draws [text] as a badge on [layers], returning new layers. The original is left untouched.
     *
     * The foreground gets a transparent ring so the badge separates from a busy icon by showing
     * the background layer through it, and the monochrome layer gets the same ring plus a knocked
     * out glyph so the badge is still legible once the launcher tints it.
     */
    fun badge(layers: IconLayers, text: String, corner: BadgeCorner): IconLayers {
        val glyph = text.trim().take(2).ifEmpty { "2" }
        val cx = CANVAS * corner.x
        val cy = CANVAS * corner.y
        val radius = CANVAS * BADGE_RADIUS
        val gap = CANVAS * BADGE_GAP

        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_FILL }
        val opaque = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        val foreground = layers.foreground.mutableCopy()
        Canvas(foreground).apply {
            drawCircle(cx, cy, radius + gap, clear)
            drawCircle(cx, cy, radius, fill)
            drawGlyph(this, glyph, cx, cy, radius, textPaint(BADGE_GLYPH, glyph.length))
        }

        val monochrome = layers.monochrome?.mutableCopy()?.also { bitmap ->
            Canvas(bitmap).apply {
                drawCircle(cx, cy, radius + gap, clear)
                drawCircle(cx, cy, radius, opaque)
                drawGlyph(this, glyph, cx, cy, radius, textPaint(Color.WHITE, glyph.length).also {
                    it.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                })
            }
        }

        return IconLayers(foreground, layers.background.mutableCopy(), monochrome)
    }

    /**
     * Flattens the layers into a plain bitmap for anything that asks for a non-adaptive icon,
     * cropped to the region a mask always keeps so nothing important is lost off the edges.
     */
    fun flatten(layers: IconLayers): Bitmap {
        val composed = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
        Canvas(composed).apply {
            drawBitmap(layers.background, 0f, 0f, null)
            drawBitmap(layers.foreground, 0f, 0f, null)
        }
        return try {
            crop(composed)
        } finally {
            composed.recycle()
        }
    }

    /**
     * The themed layer on its own, cropped the same way.
     *
     * This is what a launcher tints and draws when themed icons are on, so it is the honest way to
     * show what the badge will look like there. Null when the icon has no themed layer.
     */
    fun flattenMonochrome(layers: IconLayers): Bitmap? = layers.monochrome?.let(::crop)

    /** Masks a full 108dp layer down to the area an adaptive mask always keeps. */
    private fun crop(source: Bitmap): Bitmap {
        val inset = CANVAS / 6f // 18dp of 108dp on each side
        val size = (CANVAS - 2 * inset).toInt()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val clip = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawRoundRect(
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            size * 0.22f,
            size * 0.22f,
            clip,
        )
        clip.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, -inset, -inset, clip)
        return out
    }

    fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    // --- rendering helpers ------------------------------------------------------------------

    private fun textPaint(colour: Int, characters: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = CANVAS * BADGE_RADIUS * if (characters > 1) 1.05f else 1.35f
    }

    private fun drawGlyph(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        radius: Float,
        paint: Paint,
    ) {
        // Centre on the glyph's own extents rather than the baseline, which sits low.
        val offset = (paint.descent() + paint.ascent()) / 2f
        var size = paint.textSize
        while (paint.measureText(text) > radius * 1.6f && size > 8f) {
            size -= 2f
            paint.textSize = size
        }
        canvas.drawText(text, x, y - offset, paint)
    }

    private fun rasterise(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        val bitmap = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, CANVAS, CANVAS)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** Draws a legacy icon into the area an adaptive mask always keeps, centred. */
    private fun rasteriseInset(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
        val inset = (CANVAS * (1f - 0.62f) / 2f).toInt()
        drawable.setBounds(inset, inset, CANVAS - inset, CANVAS - inset)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun transparent(): Bitmap =
        Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)

    private fun solid(colour: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(colour)
        return bitmap
    }

    /** A flat white stand-in for a missing monochrome layer, taken from the icon's own alpha. */
    private fun silhouette(source: Bitmap): Bitmap {
        val bitmap = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        Canvas(bitmap).apply {
            drawBitmap(source, 0f, 0f, null)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            drawRect(0f, 0f, CANVAS.toFloat(), CANVAS.toFloat(), paint)
        }
        return bitmap
    }

    /** Mean colour of the opaque pixels, used as a background for icons that bring none. */
    private fun dominantColour(source: Bitmap?): Int {
        if (source == null) return 0xFF5F6368.toInt()
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val step = 8
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val pixel = source.getPixel(x, y)
                if (Color.alpha(pixel) > 128) {
                    red += Color.red(pixel)
                    green += Color.green(pixel)
                    blue += Color.blue(pixel)
                    count++
                }
                x += step
            }
            y += step
        }
        if (count == 0L) return 0xFF5F6368.toInt()
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun Bitmap.mutableCopy(): Bitmap = copy(Bitmap.Config.ARGB_8888, true)
}

/**
 * The real [IconRenderer]: asks the platform for the source app's icon, badges it, and hands back
 * encoded images. Everything that needs `Bitmap`, `Canvas` or a `Context` stops here.
 */
class AndroidIconRenderer(private val context: Context) : IconRenderer {

    override fun render(source: SourceApks, badge: String, corner: BadgeCorner): IconImages? {
        val drawable = IconFactory.loadIcon(context, source.base, source.splits) ?: return null
        val original = IconFactory.toLayers(drawable)
        val badged = try {
            IconFactory.badge(original, badge, corner)
        } finally {
            original.recycle()
        }
        return try {
            IconImages(
                foreground = IconFactory.encodePng(badged.foreground),
                background = IconFactory.encodePng(badged.background),
                monochrome = badged.monochrome?.let(IconFactory::encodePng),
                flattened = IconFactory.encodePng(IconFactory.flatten(badged)),
            )
        } finally {
            badged.recycle()
        }
    }
}
