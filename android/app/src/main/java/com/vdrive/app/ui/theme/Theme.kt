package com.vdrive.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val VDriveColorScheme = lightColorScheme(
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

@Composable
fun VDriveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VDriveColorScheme,
        typography = VDriveTypography,
        content = content
    )
}
