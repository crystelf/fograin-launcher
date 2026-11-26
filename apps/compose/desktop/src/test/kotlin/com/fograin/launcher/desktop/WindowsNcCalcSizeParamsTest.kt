package com.fograin.launcher.desktop

import com.sun.jna.Memory
import com.sun.jna.Structure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `NCCALCSIZE_PARAMS` is read straight out of a native pointer. JNA has to
 * materialise the three RECTs in the array; a silent failure here would mean
 * the Windows hook never removes the native title bar. This pins the layout
 * without needing a Windows host.
 */
class WindowsNcCalcSizeParamsTest {
    @Test
    fun readsAndWritesTheThreeClientRects() {
        val memory = Memory(3L * 16L + 8L)
        writeRect(memory, 0, left = 10, top = 20, right = 110, bottom = 220)
        writeRect(memory, 1, left = 0, top = 0, right = 1, bottom = 1)
        writeRect(memory, 2, left = 0, top = 0, right = 2, bottom = 2)

        val params = Structure.newInstance(NcCalcSizeParams::class.java, memory)
        params.read()

        val first = params.rgrc[0]
        assertNotNull(first, "JNA did not materialise rgrc[0]")
        assertEquals(10, first.left)
        assertEquals(20, first.top)
        assertEquals(110, first.right)
        assertEquals(220, first.bottom)

        first.left = 0
        first.right -= 8
        params.write()

        assertEquals(0, memory.getInt(0))
        assertEquals(102, memory.getInt(8))
    }

    private fun writeRect(memory: Memory, index: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val offset = index.toLong() * 16L
        memory.setInt(offset, left)
        memory.setInt(offset + 4, top)
        memory.setInt(offset + 8, right)
        memory.setInt(offset + 12, bottom)
    }
}