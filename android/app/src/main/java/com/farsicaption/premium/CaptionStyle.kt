package com.farsicaption.premium
/** All controls use one 1920-pixel reference height in both preview and export. */
internal object CaptionStyle {
    const val DEFAULT_FONT = 78f
    const val MIN_FONT = 30f
    const val MAX_FONT = 110f
    const val MIN_MARGIN = 40f
    const val MAX_MARGIN = 420f
    fun fontPixels(value: Float, frameHeight: Float): Float = value.coerceIn(MIN_FONT, MAX_FONT) * frameHeight / 1920f
    fun marginPixels(value: Float, frameHeight: Float): Float = value.coerceIn(MIN_MARGIN, MAX_MARGIN) * frameHeight / 1920f
    fun dragMargin(value: Float, dy: Float, frameHeight: Float): Float =
        (value - dy * 1920f / frameHeight.coerceAtLeast(1f)).coerceIn(MIN_MARGIN, MAX_MARGIN)
}
