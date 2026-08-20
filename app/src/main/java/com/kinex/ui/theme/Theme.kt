package com.kinex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark, always.
 *
 * Three things the template did that this does not, each for a reason rather than taste:
 *
 * - **It followed the system.** A gym app is used in a dark room at 6 a.m. and in a bright
 *   one at noon, and the phone's theme tracks neither. More to the point, every screen here
 *   is either a camera preview or a panel that sits beside one, and a light chrome next to
 *   live video reads as a bug.
 * - **It used dynamic colour.** The palette would then be whatever the athlete's wallpaper
 *   is. That makes [com.kinex.ui.FlaggedColor] — a fixed amber meaning "this rep was
 *   faulted" — land on an unpredictable background, and a warning colour that might collide
 *   with the accent is a warning that sometimes does not read as one.
 * - **It carried a light scheme.** Nothing selects it now, so it is gone rather than left
 *   unreachable behind a parameter no caller passes.
 *
 * There is no theme switch in Settings, deliberately. If one is ever wanted it is a real
 * feature with a stored preference, not a parameter added here in advance of anybody asking.
 */
private val KineXColorScheme = darkColorScheme(
    primary = Teal,
    onPrimary = OnTeal,
    primaryContainer = TealContainer,
    onPrimaryContainer = OnTealContainer,
    secondary = Slate,
    onSecondary = OnSlate,
    // Not cosmetic: NavigationBar draws its selected-tab pill in secondaryContainer, so
    // leaving it unset left the one persistently visible control in the app wearing M3's
    // default purple against an otherwise teal palette.
    secondaryContainer = TealContainer,
    onSecondaryContainer = OnTealContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainer = SurfaceContainer,
    outline = Outline,
    error = ErrorRed,
    onError = OnErrorRed,
)

@Composable
fun KineXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KineXColorScheme,
        typography = Typography,
        content = content,
    )
}
