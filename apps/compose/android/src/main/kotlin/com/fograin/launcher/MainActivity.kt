package com.fograin.launcher

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.toArgb
import com.fograin.launcher.generated.FograinTokens
import com.fograin.launcher.platform.AndroidPlatform
import com.fograin.launcher.platform.AppRuntime
import com.fograin.launcher.platform.createCoreClient
import com.fograin.launcher.settings.ThemeMode

class MainActivity : ComponentActivity() {

    /**
     * SAF directory tree picker for `.minecraft` import. Registered during
     * construction, as the Activity Result API requires; the shared picker in
     * `commonMain` reaches it through [AndroidPlatform].
     */
    private val treePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            AndroidPlatform.onTreePicked(uri)
        }

    /** SAF file picker for modpack / data pack zips (`.mrpack` included). */
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            AndroidPlatform.onFilePicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android has no usable $HOME for the `dirs` crate, so the data root is
        // passed before the first RPC call. The platform layer needs the
        // application context for clipboard, device info and preferences.
        AndroidPlatform.install(this)
        AndroidPlatform.attachTreePicker { treePicker.launch(null) }
        AndroidPlatform.attachFilePicker { types -> filePicker.launch(types) }
        AppRuntime.dataRoot = (getExternalFilesDir(null) ?: filesDir).absolutePath
        val core = createCoreClient()

        // Paint the starting window with the stored theme so a dark user never
        // sees a white flash before the first Compose frame.
        val darkTheme = when (core.settings.theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM ->
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        window.setBackgroundDrawable(
            ColorDrawable(
                (if (darkTheme) FograinTokens.Dark.surface else FograinTokens.Light.surface).toArgb(),
            ),
        )

        enableEdgeToEdge()
        setContent {
            App(core)
        }
    }
}
