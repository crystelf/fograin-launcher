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
import javax.swing.UIManager
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Desktop half of the two R4 shell services: an OS-native directory chooser and
 * Escape-to-back wiring.
 *
 * `JFileChooser` is a Swing window with a light default look, which feels alien
 * on macOS/Windows. We therefore ask the platform for a folder:
 *
 * - macOS: `osascript`'s `choose folder` (Finder's own sheet);
 * - Windows: a PowerShell `FolderBrowserDialog` (Explorer's common dialog);
 * - Linux: `zenity` / `kdialog` when installed.
 *
 * The Swing chooser remains the fallback (headless CI, stripped-down Linux),
 * configured with the system look and feel so it at least matches the host.
 */
internal object DesktopDirectoryPicker : JvmDirectoryBrowser() {
    override val isAvailable: Boolean
        get() = !GraphicsEnvironment.isHeadless()

    override suspend fun pick(title: String, initialPath: String?): DirectoryPick {
        if (!isAvailable) return DirectoryPick.Unavailable("headless")
        return withContext(Dispatchers.IO) {
            nativePick(title, initialPath) ?: swingPick(title, initialPath)
        }
    }
}

private data class CommandResult(val exitCode: Int, val output: String)

private fun runCommand(command: List<String>): CommandResult? = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    CommandResult(process.waitFor(), output)
}.getOrNull()

/** Returns `null` when the native path is unavailable so the Swing fallback runs. */
private fun nativePick(title: String, initialPath: String?): DirectoryPick? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val initial = initialPath?.let(::File)?.takeIf { it.isDirectory }?.absolutePath
    return when {
        os.contains("mac") -> macPick(title, initial)
        os.contains("win") -> windowsPick(title, initial)
        else -> linuxPick(title, initial)
    }
}

private fun macPick(title: String, initialPath: String?): DirectoryPick? {
    val script = buildString {
        append("POSIX path of (choose folder with prompt \"")
        append(appleScriptQuote(title))
        append('"')
        if (initialPath != null) {
            append(" default location POSIX file \"")
            append(appleScriptQuote(initialPath))
            append('"')
        }
        append(")")
    }
    val result = runCommand(listOf("osascript", "-e", script)) ?: return null
    if (result.exitCode != 0) {
        return if (result.output.contains("User canceled", ignoreCase = true) ||
            result.output.contains("-128")
        ) {
            DirectoryPick.Cancelled
        } else {
            null
        }
    }
    return picked(result.output.trim().trimEnd('/').ifEmpty { "/" })
}

private fun windowsPick(title: String, initialPath: String?): DirectoryPick? {
    val script = buildString {
        appendLine("Add-Type -AssemblyName System.Windows.Forms")
        appendLine("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8")
        appendLine("\$dialog = New-Object System.Windows.Forms.FolderBrowserDialog")
        appendLine("\$dialog.Description = '${powershellQuote(title)}'")
        appendLine("\$dialog.ShowNewFolderButton = \$true")
        if (initialPath != null) {
            appendLine("\$dialog.SelectedPath = '${powershellQuote(initialPath)}'")
        }
        appendLine(
            "if (\$dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) " +
                "{ [Console]::Write(\$dialog.SelectedPath) }",
        )
    }
    val powershell = System.getenv("SystemRoot")
        ?.let { File(it, "System32/WindowsPowerShell/v1.0/powershell.exe") }
        ?.takeIf { it.isFile }
        ?.absolutePath
        ?: "powershell.exe"
    val result = runCommand(
        listOf(powershell, "-NoProfile", "-STA", "-Command", script),
    ) ?: return null
    if (result.exitCode != 0) return null
    val path = result.output.trim()
    return if (path.isEmpty()) DirectoryPick.Cancelled else picked(path)
}

private fun linuxPick(title: String, initialPath: String?): DirectoryPick? {
    if (initialPath == null) {
        runCommand(listOf("zenity", "--file-selection", "--directory", "--title=$title"))
    } else {
        runCommand(
            listOf(
                "zenity",
                "--file-selection",
                "--directory",
                "--title=$title",
                "--filename=${initialPath.trimEnd('/')}/",
            ),
        )
    }?.let { result ->
        return when (result.exitCode) {
            0 -> picked(result.output.trim())
            1 -> DirectoryPick.Cancelled
            else -> null
        }
    }

    val kdialog = if (initialPath == null) {
        listOf("kdialog", "--getexistingdirectory", title)
    } else {
        listOf("kdialog", "--getexistingdirectory", initialPath, "--title", title)
    }
    return runCommand(kdialog)?.let { result ->
        when (result.exitCode) {
            0 -> picked(result.output.trim())
            1 -> DirectoryPick.Cancelled
            else -> null
        }
    }
}

private fun picked(path: String): DirectoryPick {
    val file = File(path)
    return if (file.isDirectory) {
        DirectoryPick.Picked(file.absolutePath)
    } else {
        DirectoryPick.Unavailable("not a directory: $path")
    }
}

private fun appleScriptQuote(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun powershellQuote(value: String): String = value.replace("'", "''")

private var systemLookAndFeelReady = false

private suspend fun swingPick(title: String, initialPath: String?): DirectoryPick =
    suspendCancellableCoroutine { continuation ->
        // Every Swing component, including the look-and-feel switch, must be
        // touched on the event dispatch thread.
        SwingUtilities.invokeLater {
            if (!systemLookAndFeelReady) {
                runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                systemLookAndFeelReady = true
            }
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
                initialPath
                    ?.let { File(it) }
                    ?.takeIf { it.isDirectory }
                    ?.let { currentDirectory = it }
            }
            val result = chooser.showOpenDialog(null)
            val choice = if (result == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath
            } else {
                null
            }
            if (continuation.isActive) {
                continuation.resume(
                    choice?.let(::picked) ?: DirectoryPick.Cancelled,
                )
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
