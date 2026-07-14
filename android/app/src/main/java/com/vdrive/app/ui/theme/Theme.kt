package com.vdrive.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val VDriveLightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Primary,
    surface = SurfaceCard,
    surfaceVariant = SurfaceSoft,
    background = Canvas,
    onBackground = Body,
    onSurface = Ink,
    outline = Hairline,
    outlineVariant = HairlineSoft,
    error = ErrorRed,
    onError = OnPrimary,
)

private val VDriveDarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Primary,
    surface = DarkSurface,
    surfaceVariant = DarkSurface,
    background = DarkCanvas,
    onBackground = DarkBody,
    onSurface = DarkInk,
    outline = DarkHairline,
    outlineVariant = DarkHairlineSoft,
    error = ErrorRed,
    onError = OnPrimary,
)

@Composable
fun VDriveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) VDriveDarkColorScheme else VDriveLightColorScheme,
        typography = VDriveTypography,
        content = content
    )
}
