package com.pocketledger.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The Material 3 base, re-themed with the pocket-ledger tokens. Nothing here
 * uses the default purple palette; every slot maps to a name in `docs/DESIGN.md`.
 *
 * v1 is light-only on purpose (the canvas has one look); a dark theme is listed
 * under Future ideas in the README.
 */
private val LedgerColorScheme = lightColorScheme(
    primary = Forest.forest,
    onPrimary = Ink.surface,
    primaryContainer = Forest.mint,
    onPrimaryContainer = Forest.forest,
    secondary = Forest.green,
    onSecondary = Ink.surface,
    secondaryContainer = Tints.mintTint,
    onSecondaryContainer = Forest.forest,
    tertiary = Tints.amberInk,
    onTertiary = Ink.surface,
    tertiaryContainer = Tints.amberTint,
    onTertiaryContainer = Tints.amberInk,
    background = Ink.ground,
    onBackground = Ink.ink,
    surface = Ink.surface,
    onSurface = Ink.ink,
    surfaceVariant = Tints.mintTint,
    onSurfaceVariant = Ink.muted,
    surfaceContainer = Ink.surface,
    surfaceContainerHigh = Ink.surface,
    surfaceContainerLow = Ink.ground,
    outline = Ink.line,
    outlineVariant = Ink.line,
    error = Tints.redInk,
    onError = Ink.surface,
    errorContainer = Tints.redTint,
    onErrorContainer = Tints.redInk,
    scrim = Ink.ink,
)

@Composable
fun PocketLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LedgerColorScheme,
        typography = LedgerTypography,
        content = content,
    )
}
