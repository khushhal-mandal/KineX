package com.kinex.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * One dark palette, fixed. The template's Purple40/Purple80 pairs are gone with the light
 * scheme and the dynamic-color branch that chose between them — see [KineXTheme].
 *
 * The accent is cool on purpose. [com.kinex.ui.FlaggedColor] is amber and marks a rep the
 * engine faulted; an accent anywhere near it would make "this is a button" and "this rep was
 * wrong" the same colour under gym lighting. Teal is far enough away to never be confused
 * with a warning, and the error red is further still.
 *
 * Surfaces are near-black rather than mid-grey because this app is mostly a camera preview
 * with controls over it. Every panel here sits next to live video, and a light chrome would
 * make the preview look dim by comparison.
 */
internal val Teal = Color(0xFF5EE0C8)
internal val OnTeal = Color(0xFF00382F)
internal val TealContainer = Color(0xFF005144)
internal val OnTealContainer = Color(0xFF7FFCE4)

internal val Slate = Color(0xFF9FB0BD)
internal val OnSlate = Color(0xFF13232E)

internal val Background = Color(0xFF0B0E11)
internal val Surface = Color(0xFF11151A)
internal val SurfaceVariant = Color(0xFF1B2127)
internal val SurfaceContainer = Color(0xFF161B21)

internal val OnBackground = Color(0xFFE3E6E8)
internal val OnSurface = Color(0xFFE3E6E8)
internal val OnSurfaceVariant = Color(0xFFAFB6BC)
internal val Outline = Color(0xFF3A424A)

internal val ErrorRed = Color(0xFFFF6B6B)
internal val OnErrorRed = Color(0xFF3A0A0A)
