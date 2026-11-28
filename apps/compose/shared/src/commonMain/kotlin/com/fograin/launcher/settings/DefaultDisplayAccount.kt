package com.fograin.launcher.settings

/**
 * Which identity the home header shows.
 *
 * `COMMUNITY` falls back to the launcher account when no community session is
 * available, so it can safely be the default.
 */
enum class DefaultDisplayAccount(val wireValue: String) {
    COMMUNITY("community"),
    LAUNCHER("launcher"),
    ;

    companion object {
        fun fromWire(value: String?): DefaultDisplayAccount =
            entries.firstOrNull { it.wireValue == value } ?: COMMUNITY
    }
}