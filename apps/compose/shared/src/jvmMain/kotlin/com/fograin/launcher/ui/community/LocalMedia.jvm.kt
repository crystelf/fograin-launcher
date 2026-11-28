package com.fograin.launcher.ui.community

import java.io.File

actual fun localMediaUri(path: String): String = File(path).toURI().toString()
