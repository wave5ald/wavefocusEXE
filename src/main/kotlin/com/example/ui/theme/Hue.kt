package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/* ==========================================================================
   Theme hue slider support.

   Every accent colour in the app is derived from the palette in Color.kt. The
   settings screen exposes a single hue slider, so instead of shipping dozens of
   hand-tuned palettes we rotate the hue of the palette at runtime. Neutrals
   (surfaces, text, outlines) are almost fully desaturated, so rotating them is
   a no-op and the layout keeps its contrast.
   ========================================================================== */

/** Hue of this colour in degrees (0..360). */
fun Color.hueDegrees(): Float {
    val r = red
    val g = green
    val b = blue
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val delta = high - low
    if (delta < 0.0001f) return 0f
    val hue = when (high) {
        r -> ((g - b) / delta) % 6f
        g -> ((b - r) / delta) + 2f
        else -> ((r - g) / delta) + 4f
    }
    return (hue * 60f + 360f) % 360f
}

/** Build a colour from HSL, mirrored after CSS `hsl()`. */
fun hueColor(
    hue: Float,
    saturation: Float = 0.85f,
    lightness: Float = 0.60f,
    alpha: Float = 1f
): Color {
    val h = ((hue % 360f) + 360f) % 360f / 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    if (s == 0f) return Color(l, l, l, alpha)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f), alpha)
}

/**
 * Rotate the hue of this colour by [degrees]. Colours with almost no saturation
 * are returned unchanged, which keeps backgrounds and text neutral.
 */
fun Color.shiftHue(degrees: Float): Color {
    val shift = degrees % 360f
    if (abs(shift) < 0.01f) return this
    val r = red
    val g = green
    val b = blue
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val lightness = (high + low) / 2f
    val delta = high - low
    if (delta < 0.02f) return this
    val saturation = if (lightness > 0.5f) {
        delta / (2f - high - low)
    } else {
        delta / (high + low)
    }
    return hueColor(hueDegrees() + shift, saturation, lightness, alpha)
}

/** The same scheme with every accent colour rotated by [degrees]. */
fun ColorScheme.shiftHue(degrees: Float): ColorScheme {
    if (abs(degrees % 360f) < 0.01f) return this
    return copy(
        primary = primary.shiftHue(degrees),
        onPrimary = onPrimary.shiftHue(degrees),
        primaryContainer = primaryContainer.shiftHue(degrees),
        onPrimaryContainer = onPrimaryContainer.shiftHue(degrees),
        inversePrimary = inversePrimary.shiftHue(degrees),
        secondary = secondary.shiftHue(degrees),
        onSecondary = onSecondary.shiftHue(degrees),
        secondaryContainer = secondaryContainer.shiftHue(degrees),
        onSecondaryContainer = onSecondaryContainer.shiftHue(degrees),
        tertiary = tertiary.shiftHue(degrees),
        onTertiary = onTertiary.shiftHue(degrees),
        tertiaryContainer = tertiaryContainer.shiftHue(degrees),
        onTertiaryContainer = onTertiaryContainer.shiftHue(degrees),
        surfaceTint = surfaceTint.shiftHue(degrees)
    )
}
