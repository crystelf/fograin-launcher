package com.fograin.launcher.platform

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Horizontal-resize cursor for the draggable settings splitter. Platforms with
 * a mouse return a resize cursor; touch-only Android keeps the default.
 */
expect fun resizeHorizontalPointerIcon(): PointerIcon
