package com.fograin.launcher.desktop

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowChromeTest {
    @Test
    fun macOSKeepsItsNativeTrafficLights() {
        assertEquals(WindowChromeKind.NATIVE, windowChromeKind("Mac OS X"))
        assertEquals(WindowChromeKind.NATIVE, windowChromeKind("macOS"))
    }

    @Test
    fun windowsAndLinuxUseTheAppDrawnCaption() {
        assertEquals(WindowChromeKind.CUSTOM, windowChromeKind("Windows 11"))
        assertEquals(WindowChromeKind.CUSTOM, windowChromeKind("Windows 10"))
        assertEquals(WindowChromeKind.CUSTOM, windowChromeKind("Linux"))
    }

    @Test
    fun customChromeCanBeForcedForDevelopment() {
        assertEquals(
            WindowChromeKind.CUSTOM,
            windowChromeKind("macOS", forceCustom = true),
        )
    }

    @Test
    fun resolvesTheCommandsTheOldMenuOwned() {
        assertEquals(DesktopCommand.NEW_INSTANCE, command(Key.N))
        assertEquals(DesktopCommand.OPEN_LIBRARY, command(Key.I))
        assertEquals(DesktopCommand.OPEN_SETTINGS, command(Key.Comma))
        assertEquals(DesktopCommand.QUIT, command(Key.Q))
        assertEquals(DesktopCommand.GLOBAL_SEARCH, command(Key.F))
        assertEquals(DesktopCommand.TOGGLE_THEME, command(Key.T, shift = true))
        assertEquals(DesktopCommand.HOME, command(Key.One))
        assertEquals(DesktopCommand.LIBRARY, command(Key.Two))
        assertEquals(DesktopCommand.DOWNLOADS, command(Key.Three))
        assertEquals(DesktopCommand.SETTINGS, command(Key.Four))
    }

    @Test
    fun ignoresUnmodifiedAndSystemShortcuts() {
        assertNull(desktopCommand(Key.N, ctrl = false, meta = false, alt = false, shift = false))
        // Win/Super belongs to the OS; Alt is reserved for menus and Alt+F4.
        assertNull(desktopCommand(Key.N, ctrl = false, meta = true, alt = false, shift = false))
        assertNull(desktopCommand(Key.N, ctrl = true, meta = false, alt = true, shift = false))
        assertNull(desktopCommand(Key.T, ctrl = true, meta = false, alt = false, shift = false))
        assertNull(desktopCommand(Key.N, ctrl = true, meta = false, alt = false, shift = true))
    }

    private fun command(key: Key, shift: Boolean = false): DesktopCommand? =
        desktopCommand(
            key = key,
            ctrl = true,
            meta = false,
            alt = false,
            shift = shift,
        )
}