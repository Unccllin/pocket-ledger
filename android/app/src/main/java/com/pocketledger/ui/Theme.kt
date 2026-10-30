package com.pocketledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Warm paper and M-Pesa green: calm, private, and readable in sunlight.
 * Spending is shown in ink, money received in green.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0E6B4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8EFE5),
    onPrimaryContainer = Color(0xFF06392A),
    secondary = Color(0xFF4E6355),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF8F4),
    onBackground = Color(0xFF1A1A18),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A18),
    surfaceVariant = Color(0xFFF1EEE7),
    onSurfaceVariant = Color(0xFF6E6A63),
    outlineVariant = Color(0xFFE6E2D9),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD3B0),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0E3B2C),
    onPrimaryContainer = Color(0xFFC8F0DF),
    secondary = Color(0xFFB7CCBD),
    onSecondary = Color(0xFF22352A),
    background = Color(0xFF121211),
    onBackground = Color(0xFFF2F0EA),
    surface = Color(0xFF1B1B19),
    onSurface = Color(0xFFF2F0EA),
    surfaceVariant = Color(0xFF242421),
    onSurfaceVariant = Color(0xFFA8A39A),
    outlineVariant = Color(0xFF33322E),
    error = Color(0xFFF2B8B5),
)

/** Money received is always green, in both themes. */
val ReceivedGreen: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF8EDCB8) else Color(0xFF0B6B47)

@Composable
fun PocketLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
