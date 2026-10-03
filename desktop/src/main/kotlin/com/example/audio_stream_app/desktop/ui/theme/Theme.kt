package com.example.audio_stream_app.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 固定的大地色系（Earth-Tone）配色。
 * 桌面端同样不提供暗色方案，不跟随系统明暗模式切换。
 */
private val EarthColorScheme = lightColorScheme(
    primary = DirtBrown,
    onPrimary = Cream,
    primaryContainer = BeigeTea,
    onPrimaryContainer = CocoaInk,
    secondary = OliveGreen,
    onSecondary = Cream,
    tertiary = CanadianLake,
    onTertiary = Cream,
    background = Cream,
    onBackground = CocoaInk,
    surface = Cream,
    onSurface = CocoaInk,
    surfaceVariant = BeigeTea,
    onSurfaceVariant = DarkChocolate,
    outline = EarthGray,
    error = TerraCotta,
    onError = Cream
)

@Composable
fun AudioSuitZuluTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = EarthColorScheme,
        content = content
    )
}
