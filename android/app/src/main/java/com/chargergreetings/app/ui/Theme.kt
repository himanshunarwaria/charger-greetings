package com.chargergreetings.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Brand palette: the same indigo-to-violet as the launcher icon and the
// Windows tray icon, so the two apps read as one product.
private val BrandIndigo = Color(0xFF6366F1)
private val BrandViolet = Color(0xFF8B5CF6)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF1A1362),
    secondary = BrandViolet,
    onSecondary = Color.White,
    background = Color(0xFFFBFBFE),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFBFBFE),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEC2FF),
    onPrimary = Color(0xFF2A2A78),
    primaryContainer = Color(0xFF404192),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = BrandIndigo,
    onSecondary = Color.White,
    background = Color(0xFF131316),
    onBackground = Color(0xFFE5E1E6),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE5E1E6),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

/**
 * App theme.
 *
 * On Android 12+ the user's wallpaper-derived colours are used, because a small
 * utility that quietly matches the rest of the system feels more at home than
 * one insisting on its own branding. The brand palette is the fallback
 * everywhere else, and both schemes are checked for Material's 4.5:1 contrast
 * on body text.
 */
@Composable
fun ChargerGreetingsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
