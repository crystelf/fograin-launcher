package com.fograin.launcher.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Host information the shell can show without waiting for `fg-sys`. */
object DeviceInfo {
    fun totalMemoryBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem
    }

    fun deviceText(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android" }

    fun socText(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.ifBlank { Build.HARDWARE }
    } else {
        Build.HARDWARE
    }
}
