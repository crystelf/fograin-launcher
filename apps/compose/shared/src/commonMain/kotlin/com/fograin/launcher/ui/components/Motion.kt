package com.fograin.launcher.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/**
 * Motion tokens for layout changes.
 *
 * The shell swaps its navigation rail for a bottom bar at a width breakpoint,
 * settings swaps its split view for the compact list, and macOS moves between
 * windowed and full-screen layouts. Those used to jump on the frame the
 * breakpoint was crossed, which reads as a flash while the window manager is
 * still animating the resize. A medium-low spring makes the new arrangement
 * follow the resize, overshoot by a hair and settle, so the layout change is
 * legible instead of instantaneous.
 *
 * Fades stay on short tweens: a spring on alpha can undershoot zero and
 * flicker. Only sizes, offsets and corner radii use the springs below.
 */
object FograinMotion {
    /** Spring for unitless layout values (progress, scale, alpha on long fades). */
    val Layout = spring<Float>(
        dampingRatio = 0.80f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Spring for widths, heights and insets. */
    val LayoutDp = spring<Dp>(
        dampingRatio = 0.80f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Spring for [androidx.compose.animation.expandHorizontally] and friends. */
    val LayoutSize = spring<IntSize>(
        dampingRatio = 0.80f,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntSize.VisibilityThreshold,
    )
}