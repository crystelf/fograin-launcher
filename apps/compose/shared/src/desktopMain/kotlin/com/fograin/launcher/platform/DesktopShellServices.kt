package com.fograin.launcher.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Desktop half of the two R4 shell services: a real OS directory chooser and
 * Escape-to-back wiring.
 */

/** Native directory chooser backed by `JFileChooser` (the only JVM-wide one). */
internal object DesktopDirectoryPicker : JvmDirectoryBrowser() {
    override val isAvailable: Boolean
        get() = !GraphicsEnvironment.isHeadless()

    override suspend fun pick(title: String, initialPath: String?): DirectoryPick {
        if (!isAvailable) return DirectoryPick.Unavailable("headless")
        return withContext(Dispatchers.IO) {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
                initialPath
                    ?.let { File(it) }
                    ?.takeIf { it.isDirectory }
                    ?.let { currentDirectory = it }
            }
            // Swing choosers must run on the event dispatch thread.
            val picked = suspendCancellableCoroutine { continuation ->
                SwingUtilities.invokeLater {
                    val result = chooser.showOpenDialog(null)
                    val choice = if (result == JFileChooser.APPROVE_OPTION) {
                        chooser.selectedFile?.absolutePath
                    } else {
                        null
                    }
                    if (continuation.isActive) continuation.resume(choice)
                }
            }
            if (picked == null) DirectoryPick.Cancelled else DirectoryPick.Picked(picked)
        }
    }
}

/**
 * Escape closes the topmost sheet/dialog and then returns to the home
 * destination, mirroring Android's back gesture through the same
 * [BackDispatcher] contract the shared shell calls.
 */
internal object DesktopBackDispatcher : BackDispatcher {
    @Composable
    override fun handleBack(enabled: Boolean, onBack: () -> Unit) {
        val current by rememberUpdatedState(onBack)
        DisposableEffect(enabled) {
            if (!enabled) return@DisposableEffect onDispose { }

            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val dispatcher = object : KeyEventDispatcher {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.id != KeyEvent.KEY_PRESSED || event.keyCode != KeyEvent.VK_ESCAPE) {
                        return false
                    }
                    current()
                    event.consume()
                    return true
                }
            }
            manager.addKeyEventDispatcher(dispatcher)
            onDispose { manager.removeKeyEventDispatcher(dispatcher) }
        }
    }
}
