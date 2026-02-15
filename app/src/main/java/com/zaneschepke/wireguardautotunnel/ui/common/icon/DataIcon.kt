package com.zaneschepke.wireguardautotunnel.ui.common.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.caverock.androidsvg.SVG
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.ceil

@Composable
fun DataIcon(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val density = LocalDensity.current
    val targetPx =
        remember(url, size, density.density) {
            with(density) { ceil(size.toPx()).toInt().coerceAtLeast(1) }
        }

    val bmp =
        remember(url, targetPx) {
            decodeSvgDataUrlToBitmap(url = url, targetSizePx = targetPx)
        }

    // If the icon can't be decoded, render nothing (client MAY ignore icon_url).
    if (bmp == null) return

    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = modifier.size(size).clip(RoundedCornerShape(6.dp)),
        filterQuality = FilterQuality.High,
    )
}

private fun decodeSvgDataUrlToBitmap(
    url: String,
    targetSizePx: Int,
): Bitmap? {
    if (url.isBlank()) return null
    if (!url.startsWith("data:", ignoreCase = true)) return null

    val commaIdx = url.indexOf(',')
    if (commaIdx <= 0 || commaIdx >= url.length - 1) return null

    val metadata = url.substring(5, commaIdx) // after "data:"
    val dataPart = url.substring(commaIdx + 1)

    if (!metadata.startsWith("image/svg+xml", ignoreCase = true)) return null

    val parts = metadata.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    val isBase64 = parts.any { it.equals("base64", ignoreCase = true) }
    val isUtf8 = parts.any { it.equals("utf8", ignoreCase = true) }

    val svgString =
        try {
            when {
                isBase64 -> {
                    val bytes = Base64.getDecoder().decode(dataPart.filterNot { it.isWhitespace() })
                    String(bytes, StandardCharsets.UTF_8)
                }

                // data:image/svg+xml,<svg...> is also acceptable; data is URL-encoded in theory.
                isUtf8 || parts.size == 1 -> java.net.URLDecoder.decode(dataPart, "UTF-8")
                else -> return null
            }
        } catch (_: Exception) {
            return null
        }

    return try {
        val svg = SVG.getFromString(svgString)

        val sizePx = targetSizePx.coerceAtLeast(1)
        val bmp = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Make AndroidSVG render into the full bitmap bounds.
        svg.setDocumentWidth(sizePx.toFloat())
        svg.setDocumentHeight(sizePx.toFloat())

        svg.renderToCanvas(canvas)
        bmp
    } catch (_: Exception) {
        null
    }
}
