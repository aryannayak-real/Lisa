package com.lisaai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Indigo = Color(0xFF4F46E5)
private val IndigoSoft = Color(0xFFA5B4FC)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    secondary = IndigoSoft,
)

private val DarkScheme = darkColorScheme(
    primary = IndigoSoft,
    secondary = Indigo,
)

@Composable
fun LisaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
