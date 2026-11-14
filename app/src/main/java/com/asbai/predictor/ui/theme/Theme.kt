package com.asbai.predictor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TurfGreen = Color(0xFF0E5A3E)
private val TurfLightGreen = Color(0xFFD7F0E5)
private val GoldAccent = Color(0xFFB38714)
private val SoftBackground = Color(0xFFF6F8F6)

private val AppColorScheme = lightColorScheme(
    primary = TurfGreen,
    onPrimary = Color.White,
    primaryContainer = TurfLightGreen,
    onPrimaryContainer = Color(0xFF062E1F),
    secondary = GoldAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF3CC),
    onSecondaryContainer = Color(0xFF3E2D00),
    background = SoftBackground,
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EEEB)
)

@Composable
fun AsbDavaniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        content = content
    )
}
