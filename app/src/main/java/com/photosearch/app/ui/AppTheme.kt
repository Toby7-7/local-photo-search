package com.photosearch.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PhotoSearchLightColorScheme = lightColorScheme(
    primary = Color(0xFF245E5A),
    onPrimary = Color.White,
    secondary = Color(0xFF6D5D2B),
    tertiary = Color(0xFF8B3F47),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE5EBEF),
    outline = Color(0xFFB8C2C8)
)

private val PhotoSearchDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7CD4CC),
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF1A4E4A),
    onPrimaryContainer = Color(0xFF9FF2E8),
    secondary = Color(0xFFCDBF8D),
    onSecondary = Color(0xFF363016),
    tertiary = Color(0xFFE7B3B0),
    onTertiary = Color(0xFF60141E),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF222426),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF2C2F33),
    onSurfaceVariant = Color(0xFFC4C6CA),
    outline = Color(0xFF8E9094),
    outlineVariant = Color(0xFF44474A)
)

@Composable
fun PhotoSearchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) PhotoSearchDarkColorScheme else PhotoSearchLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
