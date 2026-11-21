package com.fograin.launcher.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import kotlin.math.roundToInt

/**
 * The one page-to-page transition: a short fade with a small horizontal slide.
 *
 * Downloads has always moved between its list, install page and loader picker
 * with these exact timings. Library sub-pages (instance detail, instance
 * settings) and the top-level destination switch use the same spec, so every
 * page change in the launcher feels like one system instead of four.
 *
 * [forward] is usually `targetState.ordinal > initialState.ordinal`; it only
 * decides which side the two pages slide from.
 */
fun <S> AnimatedContentTransitionScope<S>.fograinPageTransition(
    forward: Boolean,
): ContentTransform {
    val enter = fadeIn(tween(200)) +
        slideInHorizontally(tween(260)) { if (forward) it / 7 else -it / 7 }
    val exit = fadeOut(tween(140)) +
        slideOutHorizontally(tween(220)) { if (forward) -it / 7 else it / 7 }
    return enter togetherWith exit
}

/**
 * Full-window page transition for shell <-> modpack import.
 *
 * Both opaque pages travel more than half a window together, covering the
 * viewport at every frame; the outgoing page fades near the end so the seam
 * disappears. Settings no longer uses this transition because its sidebar and
 * content panes animate independently.
 */
fun <S> AnimatedContentTransitionScope<S>.fograinOpaquePageTransition(
    forward: Boolean,
): ContentTransform {
    val direction = if (forward) 1 else -1
    val slide = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = 320,
        easing = FastOutSlowInEasing,
    )
    fun travel(width: Int) = (width * 0.62f).roundToInt()
    val enter = slideInHorizontally(slide) { direction * travel(it) }
    val exit = slideOutHorizontally(slide) { -direction * travel(it) } +
        fadeOut(tween(durationMillis = 140, delayMillis = 175))
    return enter togetherWith exit
}

/**
 * Duration of the shell <-> settings root transition.
 *
 * The settings page animates its own two panes during this window: the
 * sidebar slides in from the left, the content panel from the right, and the
 * splitter fades in between them. The root AnimatedContent only keeps the old
 * page alive underneath; it must not slide or fade the pages as a whole.
 */
const val SettingsRootTransitionMillis: Int = 260

/**
 * Keeps the outgoing page visible underneath the settings page for the whole
 * pane animation. [targetZIndex] puts the entering page above (settings) or
 * below (returning shell) the outgoing one.
 *
 * `EnterTransition.None` is intentional: the settings page provides its own
 * per-pane enter/exit animation, so the root must not move it a second time.
 */
fun fograinSettingsRootTransition(targetZIndex: Float): ContentTransform {
    // Keep the outgoing page fully opaque for the whole pane animation; a
    // zero-duration exit would drop it on the first frame and leave only the
    // transparent settings root, which reads as a blank flash.
    val hold = fadeOut(
        animationSpec = tween(
            durationMillis = SettingsRootTransitionMillis,
            easing = Easing { 0f },
        ),
    )
    return (EnterTransition.None togetherWith hold).apply {
        targetContentZIndex = targetZIndex
    }
}

/**
 * Settings content / compact-page transition.
 *
 * Settings pages are dense lists; the page-sized slide used by the main
 * navigation is too much motion inside one panel. A short cross-fade with a
 * 1 % scale settle keeps the panel visually anchored while still making the
 * category change legible.
 */
fun <S> AnimatedContentTransitionScope<S>.fograinSettingsTransition(): ContentTransform =
    (fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.99f)) togetherWith
        fadeOut(tween(120))

/**
 * The one in-page tab transition: the outgoing pane fades quickly while the
 * incoming pane fades in and settles from 98.5 %. Downloads categories and the
 * instance content tabs share it.
 */
fun <S> AnimatedContentTransitionScope<S>.fograinTabTransition(): ContentTransform =
    (fadeIn(tween(220)) + scaleIn(tween(240), initialScale = 0.985f)) togetherWith
        fadeOut(tween(130))
