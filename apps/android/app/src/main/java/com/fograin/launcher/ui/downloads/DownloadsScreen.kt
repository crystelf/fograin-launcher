package com.fograin.launcher.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fograin.launcher.R
import com.fograin.launcher.ui.components.EmptyState

/**
 * Downloads is an M2 milestone. M1 ships the native destination with an honest
 * empty state instead of the pen's fake progress list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        LargeTopAppBar(
            title = { Text(stringResource(R.string.downloads_title)) },
            colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = Icons.Outlined.FileDownload,
                title = stringResource(R.string.downloads_empty_title),
                body = stringResource(R.string.downloads_empty_body),
            )
        }
    }
}
