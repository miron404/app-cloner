package io.github.miron404.appcloner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rasterises an installed app's icon once and holds on to the result.
 *
 * Loading happens off the main thread: an adaptive icon has to be inflated and drawn, and a list
 * of every app on the device would stutter badly doing that inline.
 */
@Composable
fun rememberAppIcon(packageName: String?, pixels: Int = 128): ImageBitmap? {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(packageName, pixels) {
        icon = if (packageName == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(pixels, pixels)
                        .asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return icon
}

/** An app icon, falling back to a generic glyph while it loads or when the app is absent. */
@Composable
fun AppIcon(packageName: String?, size: Dp = 44.dp, contentDescription: String? = null) {
    val bitmap = rememberAppIcon(packageName)
    if (bitmap != null) {
        Image(bitmap, contentDescription, Modifier.size(size))
    } else {
        Icon(
            Icons.Default.Android,
            contentDescription,
            Modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
