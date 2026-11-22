package com.fograin.launcher.desktop

import com.fograin.launcher.platform.FileBrowser
import com.fograin.launcher.platform.FilePick
import com.fograin.launcher.platform.FileSavePick
import com.fograin.launcher.platform.PlatformServices
import com.fograin.launcher.platform.UpdateService
import com.fograin.launcher.platform.platformServices

/**
 * Host platform services with a deterministic file browser.
 *
 * Desktop tests run headless, so the real pickers cannot open; the fake save
 * target lets export flows finish without touching a dialog.
 */
internal fun testPlatform(): PlatformServices {
    val base = platformServices()
    return base.copy(
        files = object : FileBrowser {
            override val isAvailable: Boolean = true

            override suspend fun pick(title: String, extensions: List<String>): FilePick =
                FilePick.Cancelled

            override suspend fun save(
                title: String,
                suggestedName: String,
                extensions: List<String>,
            ): FileSavePick = FileSavePick.Picked(
                path = "/tmp/fograin-test/$suggestedName",
                displayPath = suggestedName,
                direct = true,
            )

            override suspend fun writePicked(
                target: FileSavePick.Picked,
                sourcePath: String,
            ): Boolean = true
        },
        updates = UpdateService.Disabled,
    )
}
