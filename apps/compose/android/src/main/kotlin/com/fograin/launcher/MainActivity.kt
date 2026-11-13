package com.fograin.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fograin.launcher.platform.AppRuntime
import com.fograin.launcher.platform.createCoreClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android has no usable $HOME for the `dirs` crate, so the data root is
        // passed before the first RPC call. Desktop leaves it to the core.
        AppRuntime.dataRoot = (getExternalFilesDir(null) ?: filesDir).absolutePath
        val core = createCoreClient()

        enableEdgeToEdge()
        setContent {
            App(core)
        }
    }
}
