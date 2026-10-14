package com.farsicaption.premium
import kotlin.math.roundToInt

internal object CaptionAss {
    fun build(
        frameWidth: Int,
        frameHeight: Int,
        captions: List<CaptionItem>,
        preset: String,
        fontSize: Int,
        marginV: Int,
        textColorKey: String,
        backgroundKey: String,
    ): String {
        val primary = when (textColorKey) {
            "white" -> "&H00FFFFFF"
            "cream" -> "&H00A6E2FF"
            "yellow" -> "&H005CE4FF"
            "cyan" -> "&H00FFE675"
            "mint" -> "&H00C8F583"
            "red" -> "&H006B6BFF"
            "black" -> "&H00111111"
            else -> when (preset) {
                "luxury" -> "&H00A6E2FF"
                "neon" -> "&H00C8F583"
                else -> "&H00FFFFFF"
            }
        }

        val (borderStyle, back) = when (backgroundKey) {
            "black60" -> 3 to "&H66000000"
            "black90" -> 3 to "&H1A000000"
            "white80" -> 3 to "&H33FFFFFF"
            "gold80" -> 3 to "&H333AA8D6"
            "navy80" -> 3 to "&H332A1A10"
            else -> 1 to "&HFF000000"
        }
        val outline = when (preset) {
            "minimal" -> 2
            "bold" -> 5
            else -> 3
        }

        val scale = frameHeight / 1920.0
        val scaledFontSize = CaptionStyle.fontPixels(fontSize.toFloat(), frameWidth.toFloat(), frameHeight.toFloat()).roundToInt()
        val scaledMargin = CaptionStyle.marginPixels(marginV.toFloat(), frameHeight.toFloat()).toInt()
        val scaledSideMargin = (60 * scale).toInt().coerceAtLeast(8)
        val scaledOutline = (outline * scale).toInt().coerceAtLeast(1)
        val scaledShadow = scale.toInt().coerceAtLeast(1)

        val header = """[Script Info]
ScriptType: v4.00+
PlayResX: $frameWidth
PlayResY: $frameHeight
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Premium,Vazirmatn,$scaledFontSize,$primary,&H00FFFFFF,&H00101010,$back,-1,0,0,0,100,100,0,0,$borderStyle,$scaledOutline,$scaledShadow,2,$scaledSideMargin,$scaledSideMargin,$scaledMargin,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""

        val events = mutableListOf<String>()

        captions.forEach { caption ->
            events += assDialogue(caption.start, caption.end, caption.text)
        }

        return header + events.joinToString("\n") + "\n"
    }

    private fun assDialogue(start: Double, end: Double, rawText: String): String {
        val clean = "\u200F" + rawText
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\n", "\\N")

        return "Dialogue: 0,${assTime(start)},${assTime(end)},Premium,,0,0,0,,$clean"
    }

    private fun assTime(seconds: Double): String {
        val cs = (seconds.coerceAtLeast(0.0) * 100.0).toInt()
        val h = cs / 360000
        val m = (cs % 360000) / 6000
        val s = (cs % 6000) / 100
        val c = cs % 100
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, c)
    }

}
