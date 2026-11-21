package com.fograin.launcher.ui.settings

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both pages are flexible inside the 32 %..68 % pane band.
 *
 * - A page only freezes when its own pane is dragged below the 32 % floor.
 * - At the resting limits (32 % left, 68 % right) both pages fit exactly, so
 *   returning to a limit never leaves the sidebar clipped at the window edge.
 * - Beyond a floor the frozen page is anchored to its outer edge (left page
 *   to the left side, content page to the right side) and the divider covers
 *   the excess.
 */
class SplitPaneMetricsTest {
    private val window = 1280.dp
    private val paneFloor = window * 0.32f
    private val defaultLeft = window / 3f

    private fun metrics(displayed: Float) = splitPaneMetrics(
        windowWidth = window,
        displayedWidth = displayed.dp,
        minPaneWidth = paneFloor,
    )

    @Test
    fun defaultSplitUsesTheWholeRightPane() {
        val page = metrics(defaultLeft.value)

        assertEquals(defaultLeft.value, page.leftPageWidth.value, 0.01f)
        assertEquals(0f, page.leftPush.value, 0.01f)
        assertEquals(window.value - defaultLeft.value, page.rightPageWidth.value, 0.01f)
        assertEquals(0f, page.rightPush.value, 0.01f)
    }

    @Test
    fun sidebarFitsTheFloorWithoutBeingPushed() {
        // Returning to the left floor must not leave the sidebar content
        // clipped at the window edge: at the limit the page is exactly the
        // pane width, with its normal left padding.
        val page = metrics(paneFloor.value)

        assertEquals(paneFloor.value, page.leftPageWidth.value, 0.01f)
        assertEquals(0f, page.leftPush.value, 0.01f)
        assertEquals(0f, page.rightPush.value, 0.01f)
    }

    @Test
    fun contentKeepsResizingInsideThePaneBand() {
        // The widest resting sidebar is 68 %; the content pane is still above
        // its 32 % floor there, so the page fills it and is not covered yet.
        val widestRestingSidebar = window.value * 0.68f
        val page = metrics(widestRestingSidebar)

        assertEquals(0f, page.rightPush.value, 0.01f)
        assertEquals(
            window.value - widestRestingSidebar,
            page.rightPageWidth.value,
            0.01f,
        )
        assertTrue(page.rightPageWidth >= paneFloor)
    }

    @Test
    fun contentPageIsPushedRightSlowerThanTheDividerBeyondTheFloor() {
        val displayed = 1000f
        val page = metrics(displayed)
        val overscroll = displayed - (window.value - paneFloor.value)

        assertEquals(paneFloor.value, page.rightPageWidth.value, 0.01f)
        assertTrue(page.rightPush < 0.dp, "content page should be pushed out beyond the floor")
        // In window coordinates the page left follows only 70 % of the
        // divider's travel to the right (the -30 % pane offset).
        assertEquals(
            window.value - paneFloor.value + overscroll * 0.7f,
            displayed + page.rightPush.value,
            0.01f,
        )
        // Its right edge therefore slides off the window and is clipped there,
        // mirroring how the left page leaves the window on the left.
        assertEquals(
            window.value + overscroll * 0.7f,
            displayed + page.rightPush.value + page.rightPageWidth.value,
            0.01f,
        )
    }

    @Test
    fun sidebarPageCanFillTheWholeWindow() {
        // The divider may travel to the right window edge; at that point the
        // sidebar page exactly fills the window, so no splitter-wide strip is
        // left behind and the whole page can be covered by the sidebar.
        val page = metrics(window.value)

        assertEquals(window.value, page.leftPageWidth.value, 0.01f)
        assertEquals(0f, page.leftPush.value, 0.01f)
    }

    @Test
    fun sidebarPageIsPushedOutOnlyBelowTheFloor() {
        val displayed = 350f
        val page = metrics(displayed)

        assertEquals(paneFloor.value, page.leftPageWidth.value, 0.01f)
        assertEquals(
            (displayed - paneFloor.value) * 0.7f,
            page.leftPush.value,
            0.01f,
        )
        assertTrue(page.leftPush < 0.dp)
    }
}
