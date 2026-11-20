package com.fograin.launcher.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

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
 * Opaque page slide used for the shell <-> settings swap.
 *
 * The two surfaces have very different content (home artwork vs. a flat
 * settings page). Cross-fading them makes the settings page translucent for a
 * few frames and shows the bright artwork underneath, which reads as a white
 * flash. Both pages stay fully opaque here and slide over each other instead;
 * the themed background behind the transition covers the window edges.
 */
fun <S> AnimatedContentTransitionScope<S>.fograinOpaquePageTransition(
    forward: Boolean,
): ContentTransform {
    val direction = if (forward) 1 else -1
    val enter = slideInHorizontally(tween(280)) { direction * it / 3 }
    val exit = slideOutHorizontally(tween(280)) { -direction * it / 3 }
    return enter togetherWith exit
}

/**
 * The one in-page tab transition: the outgoing pane fades quickly while the
 * incoming pane fades in and settles from 98.5 %. Downloads categories and the
 * instance content tabs share it.
 */
fun <S> AnimatedContentTransitionScope<S>.fograinTabTransition(): ContentTransform =
    (fadeIn(tween(220)) + scaleIn(tween(240), initialScale = 0.985f)) togetherWith
        fadeOut(tween(130))
