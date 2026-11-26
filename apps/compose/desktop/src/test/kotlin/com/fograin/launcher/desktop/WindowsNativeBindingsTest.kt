package com.fograin.launcher.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Windows frame is only reachable on a Windows host. This smoke test makes
 * the CI Windows job resolve the JNA interface (and catches a renamed or
 * mistyped win32 function) instead of discovering it at first launch.
 */
class WindowsNativeBindingsTest {
    @Test
    fun user32BindingResolvesFrameMetrics() {
        if (!System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
            return
        }
        // SM_CXFRAME; a broken binding throws UnsatisfiedLinkError here.
        assertTrue(User32Ex.INSTANCE.GetSystemMetrics(32) >= 0)
    }
}