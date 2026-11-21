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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

/**
 * Desktop file picker for `.mrpack` / `.zip` imports and data packs.
 *
 * Mirrors [DesktopDirectoryPicker]: the native panel is the primary path
 * (Finder's `choose file`, Explorer's `OpenFileDialog`, `zenity`), and a Swing
 * chooser with the system look and feel is the fallback on headless or minimal
 * Linux installs.
 */
internal object DesktopFilePicker : FileBrowser {
    override val isAvailable: Boolean
        get() = !GraphicsEnvironment.isHeadless()

    override suspend fun pick(title: String, extensions: List<String>): FilePick {
        if (!isAvailable) return FilePick.Unavailable("headless")
        return withContext(Dispatchers.IO) {
            nativePickFile(title, extensions) ?: swingPickFile(title, extensions)
        }
    }

    override suspend fun save(
        title: String,
        suggestedName: String,
        extensions: List<String>,
    ): FileSavePick {
        if (!isAvailable) return FileSavePick.Unavailable("headless")
        return withContext(Dispatchers.IO) {
            nativeSaveFile(title, suggestedName, extensions)
                ?: swingSaveFile(title, suggestedName, extensions)
        }
    }

    override suspend fun writePicked(target: FileSavePick.Picked, sourcePath: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(sourcePath)
                val destination = File(target.path)
                if (source.absolutePath == destination.absolutePath) return@withContext true
                destination.parentFile?.mkdirs()
                Files.copy(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)
        }

    override suspend fun discard(path: String) {
        withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
    }
}

private fun downloadsDirectory(): File =
    File(System.getProperty("user.home").orEmpty(), "Downloads")
        .takeIf { it.isDirectory }
        ?: File(System.getProperty("user.home").orEmpty())

private fun savedFile(path: String): FileSavePick {
    val file = File(path).absoluteFile
    return FileSavePick.Picked(path = file.absolutePath, direct = true)
}

private fun nativeSaveFile(
    title: String,
    suggestedName: String,
    extensions: List<String>,
): FileSavePick? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") -> macSaveFile(title, suggestedName)
        os.contains("win") -> windowsSaveFile(title, suggestedName, extensions)
        else -> linuxSaveFile(title, suggestedName, extensions)
    }
}

private fun macSaveFile(title: String, suggestedName: String): FileSavePick? {
    val script = buildString {
        append("POSIX path of (choose file name with prompt \"")
        append(appleScriptQuote(title))
        append("\" default name \"")
        append(appleScriptQuote(suggestedName))
        append("\" default location (path to downloads folder))")
    }
    val result = runCommand(listOf("osascript", "-e", script)) ?: return null
    if (result.exitCode != 0) {
        return if (result.output.contains("User canceled", ignoreCase = true) ||
            result.output.contains("-128")
        ) {
            FileSavePick.Cancelled
        } else {
            null
        }
    }
    return savedFile(result.output.trim())
}

private fun windowsSaveFile(
    title: String,
    suggestedName: String,
    extensions: List<String>,
): FileSavePick? {
    val filter = if (extensions.isEmpty()) "*.*" else extensions.joinToString(";") { "*.$it" }
    val command = buildString {
        append("Add-Type -AssemblyName System.Windows.Forms; ")
        append("\$d = New-Object System.Windows.Forms.SaveFileDialog; ")
        append("\$d.Title = '")
        append(powershellQuote(title))
        append("'; ")
        append("\$d.FileName = '")
        append(powershellQuote(suggestedName))
        append("'; ")
        append("\$d.Filter = '支持的文件|")
        append(filter)
        append("'; ")
        append("\$d.InitialDirectory = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads'; ")
        append("\$d.OverwritePrompt = \$true; ")
        append("if (\$d.ShowDialog() -eq 'OK') { Write-Output \$d.FileName }")
    }
    val result = runCommand(
        listOf("powershell", "-NoProfile", "-STA", "-Command", command),
    ) ?: return null
    if (result.exitCode != 0) return FileSavePick.Unavailable("powershell")
    val path = result.output.trim()
    return if (path.isEmpty()) FileSavePick.Cancelled else savedFile(path)
}

private fun linuxSaveFile(
    title: String,
    suggestedName: String,
    extensions: List<String>,
): FileSavePick? {
    val filters = extensions
        .filter { it.isNotBlank() }
        .joinToString(" ") { "*.$it" }
    val initial = File(downloadsDirectory(), suggestedName).absolutePath
    val command = buildList {
        add("zenity")
        add("--file-selection")
        add("--save")
        add("--confirm-overwrite")
        add("--title=$title")
        add("--filename=$initial")
        if (filters.isNotEmpty()) add("--file-filter=支持的文件 | $filters")
    }
    val result = runCommand(command) ?: return null
    if (result.exitCode == 1) return FileSavePick.Cancelled
    if (result.exitCode != 0) return null
    return savedFile(result.output.trim())
}

private suspend fun swingSaveFile(
    title: String,
    suggestedName: String,
    extensions: List<String>,
): FileSavePick = suspendCancellableCoroutine { continuation ->
    SwingUtilities.invokeLater {
        if (!systemLookAndFeelReady) {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            systemLookAndFeelReady = true
        }
        val chooser = JFileChooser().apply {
            dialogTitle = title
            dialogType = JFileChooser.SAVE_DIALOG
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = extensions.isEmpty()
            selectedFile = File(downloadsDirectory(), suggestedName)
            if (extensions.isNotEmpty()) {
                fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                    extensions.joinToString(", "),
                    *extensions.toTypedArray(),
                )
            }
        }
        val result = chooser.showSaveDialog(null)
        var choice: String? = if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
        val selected = choice
        if (selected != null && extensions.isNotEmpty() &&
            extensions.none { selected.endsWith(".$it", ignoreCase = true) }
        ) {
            choice = "$selected.${extensions.first()}"
        }
        if (continuation.isActive) {
            continuation.resume(choice?.let(::savedFile) ?: FileSavePick.Cancelled)
        }
    }
}

private fun nativePickFile(title: String, extensions: List<String>): FilePick? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") -> macPickFile(title, extensions)
        os.contains("win") -> windowsPickFile(title, extensions)
        else -> linuxPickFile(title, extensions)
    }
}

private fun macPickFile(title: String, extensions: List<String>): FilePick? {
    val types = extensions
        .filter { it.isNotBlank() }
        .joinToString(", ") { "\"${appleScriptQuote(it)}\"" }
    val script = buildString {
        append("POSIX path of (choose file with prompt \"")
        append(appleScriptQuote(title))
        append('"')
        if (types.isNotEmpty()) {
            append(" of type {")
            append(types)
            append('}')
        }
        append(")")
    }
    val result = runCommand(listOf("osascript", "-e", script)) ?: return null
    if (result.exitCode != 0) {
        return if (result.output.contains("User canceled", ignoreCase = true) ||
            result.output.contains("-128")
        ) {
            FilePick.Cancelled
        } else {
            null
        }
    }
    return pickedFile(result.output.trim())
}

private fun windowsPickFile(title: String, extensions: List<String>): FilePick? {
    val filter = if (extensions.isEmpty()) {
        "*.*"
    } else {
        extensions.joinToString(";") { "*.$it" }
    }
    val command = buildString {
        append("Add-Type -AssemblyName System.Windows.Forms; ")
        append("\$d = New-Object System.Windows.Forms.OpenFileDialog; ")
        append("\$d.Title = '")
        append(powershellQuote(title))
        append("'; ")
        append("\$d.Filter = '支持的文件|")
        append(filter)
        append("'; ")
        append("if (\$d.ShowDialog() -eq 'OK') { Write-Output \$d.FileName }")
    }
    val result = runCommand(
        listOf("powershell", "-NoProfile", "-STA", "-Command", command),
    ) ?: return null
    if (result.exitCode != 0) return FilePick.Unavailable("powershell")
    val path = result.output.trim()
    return if (path.isEmpty()) FilePick.Cancelled else pickedFile(path)
}

private fun linuxPickFile(title: String, extensions: List<String>): FilePick? {
    val filters = extensions
        .filter { it.isNotBlank() }
        .joinToString(" ") { "*.$it" }
    val zenityCommand = buildList {
        add("zenity")
        add("--file-selection")
        add("--title=$title")
        if (filters.isNotEmpty()) add("--file-filter=支持的文件 | $filters")
    }
    val result = runCommand(zenityCommand) ?: return null
    if (result.exitCode == 1) {
        // zenity's documented "cancel" code; do not reopen a Swing dialog.
        return FilePick.Cancelled
    }
    if (result.exitCode != 0) return null
    return pickedFile(result.output.trim())
}

private fun pickedFile(path: String): FilePick {
    val file = File(path)
    return if (file.isFile && file.canRead()) {
        FilePick.Picked(file.absolutePath)
    } else {
        FilePick.Unavailable("not a file: $path")
    }
}

private suspend fun swingPickFile(title: String, extensions: List<String>): FilePick =
    suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            if (!systemLookAndFeelReady) {
                runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                systemLookAndFeelReady = true
            }
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = extensions.isEmpty()
                if (extensions.isNotEmpty()) {
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                        extensions.joinToString(", "),
                        *extensions.toTypedArray(),
                    )
                }
            }
            val result = chooser.showOpenDialog(null)
            val choice = if (result == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath
            } else {
                null
            }
            if (continuation.isActive) {
                continuation.resume(choice?.let(::pickedFile) ?: FilePick.Cancelled)
            }
        }
    }
