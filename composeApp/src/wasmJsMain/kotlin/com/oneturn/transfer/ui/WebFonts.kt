package com.oneturn.transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont
import transferp2p.composeapp.generated.resources.Res
import transferp2p.composeapp.generated.resources.noto_sans_sc_regular

private val Primary = Color(0xFF0E7490)
private val OnPrimary = Color(0xFFFFFFFF)
private val Background = Color(0xFFF6F7F9)
private val OnBackground = Color(0xFF1A1C1E)
private val Surface = Color(0xFFFFFFFF)
private val OnSurface = Color(0xFF1A1C1E)
private val SurfaceVariant = Color(0xFFEEF1F4)
private val OnSurfaceVariant = Color(0xFF3C4043)
private val LoadingBg = Color(0xFFF6F7F9)

/**
 * Skiko/wasmJs has no OS font fallback, so CJK glyphs are blank unless we load a Chinese font.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun WithChineseFont(content: @Composable () -> Unit) {
    val loaded by preloadFont(Res.font.noto_sans_sc_regular, FontWeight.Normal)
    val font = loaded
    if (font == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LoadingBg),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Primary)
        }
        return
    }

    val family = FontFamily(font)
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(family) {
        resolver.preload(family)
    }

    val base = MaterialTheme.typography
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Primary,
            onPrimary = OnPrimary,
            primaryContainer = Color(0xFFC8E6F0),
            onPrimaryContainer = Color(0xFF06323F),
            secondary = Color(0xFF3D8C4C),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFD3EBD8),
            onSecondaryContainer = Color(0xFF0B3A15),
            background = Background,
            onBackground = OnBackground,
            surface = Surface,
            onSurface = OnSurface,
            surfaceVariant = SurfaceVariant,
            onSurfaceVariant = OnSurfaceVariant,
            surfaceContainerLow = Color(0xFFFCFCFD),
            surfaceContainer = SurfaceVariant,
            surfaceContainerHigh = Color(0xFFE4E8EC),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
        ),
        typography = typography,
        content = content,
    )
}
