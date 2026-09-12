package com.masterofchessstrategy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Jade,
    onPrimary = Paper,
    background = Parchment,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = PaleJade,
    onPrimary = NightInk,
    background = NightInk,
    onBackground = Paper,
    surface = NightPaper,
    onSurface = Paper,
)

@Composable
fun MocsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MocsTypography,
        content = content,
    )
}
