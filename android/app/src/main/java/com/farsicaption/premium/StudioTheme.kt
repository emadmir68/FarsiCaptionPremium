package com.farsicaption.premium

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection

internal val Ink = Color(0xFF0C1020)
internal val Panel = Color(0xFF161C30)
internal val Panel2 = Color(0xFF232B44)
internal val Accent = Color(0xFFB5A0FF)
internal val AccentSoft = Color(0xFFDBD0FF)
internal val Mint = Color(0xFF7DE4D3)
internal val TextPrimary = Color(0xFFF5F5FC)
internal val TextMuted = Color(0xFFA6B0C8)
internal val Stroke = Color(0xFF333E59)

@Composable
internal fun StudioTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val typography = remember {
        val font = FontFamily(androidx.compose.ui.text.font.Typeface(
            android.graphics.Typeface.createFromAsset(context.assets, "fonts/Vazirmatn-Regular.ttf")))
        val t = Typography()
        Typography(
            displayLarge=t.displayLarge.copy(fontFamily=font), displayMedium=t.displayMedium.copy(fontFamily=font), displaySmall=t.displaySmall.copy(fontFamily=font),
            headlineLarge=t.headlineLarge.copy(fontFamily=font), headlineMedium=t.headlineMedium.copy(fontFamily=font), headlineSmall=t.headlineSmall.copy(fontFamily=font),
            titleLarge=t.titleLarge.copy(fontFamily=font), titleMedium=t.titleMedium.copy(fontFamily=font), titleSmall=t.titleSmall.copy(fontFamily=font),
            bodyLarge=t.bodyLarge.copy(fontFamily=font), bodyMedium=t.bodyMedium.copy(fontFamily=font), bodySmall=t.bodySmall.copy(fontFamily=font),
            labelLarge=t.labelLarge.copy(fontFamily=font), labelMedium=t.labelMedium.copy(fontFamily=font), labelSmall=t.labelSmall.copy(fontFamily=font),
        )
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = darkColorScheme(primary=Accent, onPrimary=Ink, secondary=Mint, onSecondary=Ink,
                background=Ink, onBackground=TextPrimary, surface=Panel, onSurface=TextPrimary,
                surfaceVariant=Panel2, onSurfaceVariant=TextMuted, outline=Stroke),
            typography=typography, content=content,
        )
    }
}
