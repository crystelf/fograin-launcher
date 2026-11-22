package com.fograin.launcher.platform

/**
 * Fetches a small UTF-8 text document for the UI layer (version artwork feed,
 * mod metadata). Implemented on the JVM so Android and desktop share one HTTP
 * client; returns `null` on any transport or HTTP error.
 */
internal expect suspend fun fetchTextDocument(url: String): String?
