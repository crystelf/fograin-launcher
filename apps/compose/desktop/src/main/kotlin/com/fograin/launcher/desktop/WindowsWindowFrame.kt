package com.fograin.launcher.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.fograin.launcher.ui.components.WindowCaptionRegionReporter
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Container
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skiko.SkiaLayer

/**
 * Windows keeps the native frame and DWM behaviours while the launcher draws
 * its own caption.
 *
 * This is the approach Ani uses and the reason the buttons could stay in the
 * page header at all. The window is created with
 * [androidx.compose.ui.window.WindowDecoration.SystemDefault], so AWT gives it
 * the normal `WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX`
 * styles. Those styles are what make DWM animate minimising/maximising,
 * provide Aero Snap and the system shadow. We then hide only the *visible*
 * non-client area:
 *
 * - `WM_NCCALCSIZE` reports the whole window rectangle as client area, so the
 *   native title bar disappears but the frame styles stay.
 * - `WM_NCHITTEST` gives the resize borders back to `DefWindowProc`, and turns
 *   the Compose title strips into `HTCAPTION` so the OS owns dragging,
 *   double-click maximize and Snap.
 *
 * A `WM_NCHITTEST` on the Skia child is handled first by Windows. It answers
 * `HTTRANSPARENT` for the caption/border and `HTCLIENT` for everything else,
 * which forwards the non-client areas to the top-level procedure without
 * stealing Compose's mouse input.
 */

/** Caption/border regions in top-level client coordinates. */
internal class WindowsCaptionRegions : WindowCaptionRegionReporter {
    private val regions = ConcurrentHashMap<Any, Rect>()

    override fun update(key: Any, bounds: Rect) {
        if (bounds.width <= 0f || bounds.height <= 0f) {
            regions.remove(key)
        } else {
            regions[key] = bounds
        }
    }

    override fun remove(key: Any) {
        regions.remove(key)
    }

    fun contains(x: Float, y: Float): Boolean =
        regions.values.any { it.contains(Offset(x, y)) }
}

/**
 * Installs the non-client-area hooks; returns a handle that restores the
 * previous window procedures.
 *
 * Only called on Windows. The top-level hook hides the native caption and is
 * installed immediately; the Skia child is retried until it exists, because it
 * is what turns the Compose title strips into native captions.
 */
internal fun installWindowsFrame(
    window: ComposeWindow,
    regions: WindowsCaptionRegions,
): AutoCloseable {
    val topHandle = window.windowHandle
    if (topHandle == 0L) return AutoCloseable {}
    return WindowsFrameHandle(window, topHandle, regions)
}

/**
 * Owns the top-level procedure and the Skia child procedures.
 *
 * The child HWND only exists after the Compose layer has been added to the
 * window, which can happen a moment after composition on some AWT builds. The
 * handle retries on the first show/resize instead of silently leaving the
 * window without border hit-testing.
 */
private class WindowsFrameHandle(
    private val window: ComposeWindow,
    private val topHandle: Long,
    private val regions: WindowsCaptionRegions,
) : AutoCloseable {
    private val user32 = User32Ex.INSTANCE
    private val topLevel = HWND(Pointer(topHandle))
    private val topProc = TopLevelWindowProc(user32, topLevel, regions)
    private val childProcs = mutableListOf<ChildWindowProc>()

    private val retryListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) {
            installChildProcs()
        }

        override fun componentResized(event: ComponentEvent) {
            installChildProcs()
        }
    }

    init {
        installChildProcs()
        if (childProcs.isEmpty()) {
            window.addComponentListener(retryListener)
        }
    }

    private fun installChildProcs() {
        if (childProcs.isNotEmpty()) return
        val skiaLayer = window.findSkiaLayer() ?: return
        // Only real HWNDs: the content canvas (the window that receives
        // WM_NCHITTEST) and Skiko's own layer window. `contentHandle` is a
        // render-surface handle on some backends, not a window, and hooking it
        // would corrupt an unrelated object.
        val handles = buildSet<Long> {
            runCatching { Native.getComponentPointer(skiaLayer.canvas) }.getOrNull()?.let {
                add(Pointer.nativeValue(it))
            }
            add(skiaLayer.windowHandle)
        }
        handles.filter { it != 0L && it != topHandle }.forEach { handle ->
            runCatching {
                val proc = ChildWindowProc(user32, topLevel, HWND(Pointer(handle)), regions)
                if (proc.installed) childProcs += proc
            }
        }
        if (childProcs.isNotEmpty()) {
            window.removeComponentListener(retryListener)
        }
    }

    override fun close() {
        window.removeComponentListener(retryListener)
        childProcs.forEach { runCatching { it.close() } }
        runCatching { topProc.close() }
    }
}

private const val GWL_WNDPROC = -4
private const val WM_NCCALCSIZE = 0x0083
private const val WM_NCHITTEST = 0x0084
private const val HTTRANSPARENT = -1
private const val HTCLIENT = 1
private const val HTCAPTION = 2
private const val HTLEFT = 10
private const val HTRIGHT = 11
private const val HTTOP = 12
private const val HTTOPLEFT = 13
private const val HTTOPRIGHT = 14
private const val HTBOTTOM = 15
private const val HTBOTTOMLEFT = 16
private const val HTBOTTOMRIGHT = 17
private const val SM_CXFRAME = 32
private const val SM_CYFRAME = 33
private const val SM_CXPADDEDBORDER = 92

internal interface User32Ex : StdCallLibrary {
    fun SetWindowLongPtr(hWnd: HWND, nIndex: Int, dwNewLong: Pointer?): Pointer?

    fun CallWindowProc(
        lpPrevWndFunc: Pointer,
        hWnd: HWND,
        msg: Int,
        wParam: WPARAM,
        lParam: LPARAM,
    ): LRESULT

    fun GetClientRect(hWnd: HWND, lpRect: RECT): Boolean

    fun ScreenToClient(hWnd: HWND, lpPoint: POINT): Boolean

    fun GetSystemMetrics(nIndex: Int): Int

    fun GetSystemMetricsForDpi(nIndex: Int, dpi: Int): Int

    fun GetDpiForWindow(hWnd: HWND): Int

    fun IsZoomed(hWnd: HWND): Boolean

    companion object {
        val INSTANCE: User32Ex by lazy {
            Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}

@Structure.FieldOrder("rgrc", "lppos")
internal class NcCalcSizeParams : Structure(), Structure.ByReference {
    @JvmField
    var rgrc: Array<RECT?> = arrayOfNulls(3)

    @JvmField
    var lppos: Pointer? = null
}

private class TopLevelWindowProc(
    private val user32: User32Ex,
    private val hwnd: HWND,
    private val regions: WindowsCaptionRegions,
) : WinUser.WindowProc, AutoCloseable {
    private val previous: Pointer? = user32.SetWindowLongPtr(
        hwnd,
        GWL_WNDPROC,
        CallbackReference.getFunctionPointer(this),
    )

    override fun callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
        return try {
            when (uMsg) {
                WM_NCCALCSIZE -> if (wParam.toInt() == 0) {
                    callDefault(hwnd, uMsg, wParam, lParam)
                } else {
                    hideNonClientArea(hwnd, lParam)
                }

                WM_NCHITTEST -> LRESULT(hitTest(lParam).toLong())
                else -> callDefault(hwnd, uMsg, wParam, lParam)
            }
        } catch (_: Throwable) {
            runCatching { callDefault(hwnd, uMsg, wParam, lParam) }.getOrElse { LRESULT(0) }
        }
    }

    /**
     * Returning zero with `wParam == TRUE` tells Windows the app handled the
     * client-area calculation. The frame styles are untouched, so DWM keeps
     * animating/minimising as usual; only the pixels of the native caption
     * disappear.
     */
    private fun hideNonClientArea(hwnd: HWND, lParam: LPARAM): LRESULT {
        val params = runCatching {
            Structure.newInstance(NcCalcSizeParams::class.java, Pointer(lParam.toLong()))
                .also { it.read() }
        }.getOrNull() ?: return LRESULT(0)

        // A maximized window is placed slightly outside the monitor work area
        // to hide the frame. Inset the client rectangle by the frame metrics
        // so no content is clipped at the edges. Ani uses the horizontal
        // metric for all four sides because SM_CYFRAME differs on some themes.
        if (user32.IsZoomed(hwnd)) {
            val dpi = dpiOf(hwnd)
            val frame = metric(SM_CXFRAME, dpi) + metric(SM_CXPADDEDBORDER, dpi)
            params.rgrc[0]?.let { rect ->
                rect.left += frame
                rect.right -= frame
                rect.top += frame
                rect.bottom -= frame
            }
            params.write()
        }
        return LRESULT(0)
    }

    private fun hitTest(lParam: LPARAM): Int {
        val point = lParam.toClientPoint(hwnd)
        borderCode(point)?.let { return it }
        if (regions.contains(point.x.toFloat(), point.y.toFloat())) return HTCAPTION
        return HTCLIENT
    }

    private fun borderCode(point: Point): Int? {
        if (user32.IsZoomed(hwnd)) return null
        val client = RECT()
        if (!user32.GetClientRect(hwnd, client)) return null
        client.read()
        val dpi = dpiOf(hwnd)
        val marginX = metric(SM_CXFRAME, dpi).coerceAtLeast(4)
        val marginY = metric(SM_CYFRAME, dpi).coerceAtLeast(4)
        val x = point.x
        val y = point.y
        val width = client.right
        val height = client.bottom
        return when {
            x < marginX && y < marginY -> HTTOPLEFT
            x >= width - marginX && y < marginY -> HTTOPRIGHT
            x < marginX && y >= height - marginY -> HTBOTTOMLEFT
            x >= width - marginX && y >= height - marginY -> HTBOTTOMRIGHT
            x < marginX -> HTLEFT
            x >= width - marginX -> HTRIGHT
            y < marginY -> HTTOP
            y >= height - marginY -> HTBOTTOM
            else -> null
        }
    }

    private fun dpiOf(hwnd: HWND): Int =
        runCatching { user32.GetDpiForWindow(hwnd) }.getOrDefault(96).takeIf { it > 0 } ?: 96

    private fun metric(index: Int, dpi: Int): Int =
        runCatching { user32.GetSystemMetricsForDpi(index, dpi) }
            .getOrElse { user32.GetSystemMetrics(index) }
            .coerceAtLeast(0)

    private fun callDefault(hwnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT =
        previous?.let { user32.CallWindowProc(it, hwnd, msg, wParam, lParam) } ?: LRESULT(0)

    override fun close() {
        previous?.let { user32.SetWindowLongPtr(hwnd, GWL_WNDPROC, it) }
    }
}

private class ChildWindowProc(
    private val user32: User32Ex,
    private val topLevel: HWND,
    private val hwnd: HWND,
    private val regions: WindowsCaptionRegions,
) : WinUser.WindowProc, AutoCloseable {
    private val previous: Pointer? = user32.SetWindowLongPtr(
        hwnd,
        GWL_WNDPROC,
        CallbackReference.getFunctionPointer(this),
    )

    val installed: Boolean get() = previous != null

    override fun callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
        return try {
            when (uMsg) {
                WM_NCHITTEST -> {
                    val point = lParam.toClientPoint(topLevel)
                    val isCaption = regions.contains(point.x.toFloat(), point.y.toFloat())
                    if (isCaption || isBorder(point)) {
                        // Let the top-level procedure answer with CAPTION or a
                        // resize border; returning CLIENT here would keep the
                        // pixels but give away native drag and resizing.
                        LRESULT(HTTRANSPARENT.toLong())
                    } else {
                        LRESULT(HTCLIENT.toLong())
                    }
                }

                else -> callDefault(hwnd, uMsg, wParam, lParam)
            }
        } catch (_: Throwable) {
            runCatching { callDefault(hwnd, uMsg, wParam, lParam) }.getOrElse { LRESULT(0) }
        }
    }

    private fun isBorder(point: Point): Boolean {
        if (user32.IsZoomed(topLevel)) return false
        val client = RECT()
        if (!user32.GetClientRect(topLevel, client)) return false
        client.read()
        val dpi = runCatching { user32.GetDpiForWindow(topLevel) }.getOrDefault(96)
            .takeIf { it > 0 } ?: 96
        val marginX = runCatching { user32.GetSystemMetricsForDpi(SM_CXFRAME, dpi) }
            .getOrElse { user32.GetSystemMetrics(SM_CXFRAME) }
            .coerceAtLeast(4)
        val marginY = runCatching { user32.GetSystemMetricsForDpi(SM_CYFRAME, dpi) }
            .getOrElse { user32.GetSystemMetrics(SM_CYFRAME) }
            .coerceAtLeast(4)
        return point.x < marginX ||
            point.y < marginY ||
            point.x >= client.right - marginX ||
            point.y >= client.bottom - marginY
    }

    private fun callDefault(hwnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT =
        previous?.let { user32.CallWindowProc(it, hwnd, msg, wParam, lParam) } ?: LRESULT(0)

    override fun close() {
        previous?.let { user32.SetWindowLongPtr(hwnd, GWL_WNDPROC, it) }
    }
}

private fun LPARAM.toClientPoint(window: HWND): Point {
    val value = toInt()
    val screenX = (value and 0xFFFF).toShort().toInt()
    val screenY = ((value shr 16) and 0xFFFF).toShort().toInt()
    val point = POINT(screenX, screenY)
    runCatching { User32Ex.INSTANCE.ScreenToClient(window, point) }
    point.read()
    return Point(point.x, point.y)
}

private fun ComposeWindow.findSkiaLayer(): SkiaLayer? = findComponent(this)

private fun findComponent(container: Container): SkiaLayer? {
    for (child in container.components) {
        if (child is SkiaLayer) return child
        if (child is Container) {
            findComponent(child)?.let { return it }
        }
    }
    return null
}