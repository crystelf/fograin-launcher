package com.fograin.launcher.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.fograin.launcher.platform.platformServices

/** Radius of the content pane's left edge where the host window is rounded. */
private val ContentPaneCorner = 18.dp

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
 * Shape for the pane that sits right of the navigation rail / settings
 * sidebar. Rounds the two left corners and leaves the other two square, so
 * the pane merges into the system window instead of looking like a floating
 * card.
 */
@Composable
fun windowContentShape(): Shape {
    val rounded = remember { windowHasRoundedCorners(platformServices().platformName) }
    return if (rounded) {
        RoundedCornerShape(topStart = ContentPaneCorner, bottomStart = ContentPaneCorner)
    } else {
        RectangleShape
    }
}
