package com.example.sompo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = SompoVermelho,
    onPrimary = Color.White,
    secondary = SompoVermelhoClaro,
    background = Color(0xFFF7F7F9),
    surface = Color.White
)

@Composable
fun SompoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
