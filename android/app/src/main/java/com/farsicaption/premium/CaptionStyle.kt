package com.farsicaption.premium
/** Preview and export share the same frame-relative geometry. */
internal object CaptionStyle {
    const val DEFAULT_FONT = 78f
    const val DEFAULT_LANDSCAPE_FONT = 90f
    const val MIN_FONT = 30f
    const val MAX_FONT = 110f
    const val MAX_LANDSCAPE_FONT = 150f
    const val MIN_MARGIN = 40f
    const val MAX_MARGIN = 420f
    fun fontPixels(value: Float, frameHeight: Float): Float = value.coerceIn(MIN_FONT, MAX_FONT) * frameHeight / 1920f
    fun isLandscape(width: Float, height: Float) = width > height && height > 0f
    fun maxFont(landscape: Boolean) = if (landscape) MAX_LANDSCAPE_FONT else MAX_FONT
    fun defaultFont(landscape: Boolean) = if (landscape) DEFAULT_LANDSCAPE_FONT else DEFAULT_FONT
    fun selectedFont(portrait: Float, landscape: Float, width: Float, height: Float) =
        (if (isLandscape(width, height)) landscape else portrait).coerceIn(MIN_FONT, maxFont(isLandscape(width, height)))
    fun fontPixels(value: Float, width: Float, height: Float): Float {
        // A 16:9 clip occupies far less screen height on a portrait phone. Increase
        // its relative type size smoothly, without changing portrait preferences.
        val ratio = width / height.coerceAtLeast(1f)
        val boost = 1f + 0.6f * ((ratio - 1f) / (16f / 9f - 1f)).coerceIn(0f, 1f)
        return value.coerceIn(MIN_FONT, maxFont(isLandscape(width, height))) * height / 1920f * boost
    }
    fun marginPixels(value: Float, frameHeight: Float): Float = value.coerceIn(MIN_MARGIN, MAX_MARGIN) * frameHeight / 1920f
    fun dragMargin(value: Float, dy: Float, frameHeight: Float): Float =
        (value - dy * 1920f / frameHeight.coerceAtLeast(1f)).coerceIn(MIN_MARGIN, MAX_MARGIN)
}
