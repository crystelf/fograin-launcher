package com.fograin.launcher.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.fograin.launcher.platform.platformServices

/**
 * Whether the host window itself has rounded corners.
 *
 * The content pane is flush with the window's top, right and bottom edges, so
 * its right side must follow the system's window shape instead of drawing an
 * inner card. Only the left edge is rounded, and only when the window is:
 * macOS, Windows 11 (build 22000+) and GNOME round their windows; Windows
 * 10/7 and fullscreen Android do not, so those stay rectangular.
 */
fun windowHasRoundedCorners(platformName: String): Boolean = when (platformName) {
    "macos", "win11", "gnome" -> true
    else -> false
}

/**
 * Radius of the host window's own corners, so the inner pane is cut out with
 * the same curve instead of a second, unrelated radius.
 *
 * macOS and GNOME use a 12 pt window radius, Windows 11 uses 8 px. The
 * settings card and the shell pane share this value so their curves match the
 * host window instead of drifting by a couple of pixels. Platforms without
 * rounded windows return 0.
 */
private fun windowCornerRadius(platformName: String) = when (platformName) {
    "macos" -> 12.dp
    "win11" -> 8.dp
    "gnome" -> 12.dp
    else -> 0.dp
}

/**
 * Shape for the pane that sits right of the navigation rail / settings
 * sidebar. Rounds the two left corners with the host window's radius and
 * leaves the other two square, so the pane merges into the window and the
 * sidebar colour shows through the cut-outs.
 */
@Composable
fun windowContentShape(): Shape {
    val platformName = remember { platformServices().platformName }
    val radius = windowCornerRadius(platformName)
    return if (windowHasRoundedCorners(platformName)) {
        RoundedCornerShape(topStart = radius, bottomStart = radius)
    } else {
        RectangleShape
    }
}

/** Material 3 `extraLarge`, used by inset content panels (as in Ani). */
private val ContentPanelCorner = 28.dp

/**
 * Shape of a page content panel.
 *
 * While the panel is inset from the window's left edge (rail / settings
 * sidebar) it uses the large `extraLarge` corner from the reference. Once the
 * panel sits on the window edge ([inset] false) it falls back to
 * [windowContentShape] so its curve coincides with the system window corner.
 * Every page uses this one function, so the shell and settings match.
 */
@Composable
fun contentPanelShape(inset: Boolean): Shape {
    if (!inset) return windowContentShape()
    return RoundedCornerShape(
        topStart = ContentPanelCorner,
        bottomStart = ContentPanelCorner,
    )
}
