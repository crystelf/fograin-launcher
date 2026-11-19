package com.fograin.launcher.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The drag-and-drop filter accepts archives and MultiMC/Prism folders only. */
class DropTargetTest {
    @Test
    fun acceptsArchivesAndDirectories() {
        val temp = File(System.getProperty("java.io.tmpdir"), "fograin-drop-${System.nanoTime()}")
        assertTrue(temp.mkdirs())
        try {
            assertTrue(isSupportedDropFile(temp))
            assertTrue(isSupportedDropFile(File(temp, "calm.mrpack")))
            assertTrue(isSupportedDropFile(File(temp, "pack.ZIP")))
            assertFalse(isSupportedDropFile(File(temp, "notes.txt")))
            assertFalse(isSupportedDropFile(File(temp, "mod.jar")))
        } finally {
            temp.deleteRecursively()
        }
    }
}
