package com.monoapps.monomusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monoapps.monomusic.MonoMusicViewModel
import com.monoapps.monomusic.offline.OfflineManager
import com.monoapps.monomusic.tidal.AudioQuality
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.text.TextMMD

@Composable
fun SettingsScreen(viewModel: MonoMusicViewModel, onOpenDownloads: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        TextMMD(text = "Streaming quality", fontSize = 22.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(8.dp))
        AudioQuality.entries.forEach { quality ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { viewModel.selectAudioQuality(quality) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButtonMMD(
                    selected = viewModel.audioQuality == quality,
                    onClick = { viewModel.selectAudioQuality(quality) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextMMD(text = quality.label, fontSize = 16.sp)
            }
        }
        TextMMD(
            text = "Takes effect from the next song. Higher quality uses more data.",
            fontSize = 13.sp,
        )

        Spacer(modifier = Modifier.height(24.dp))
        DashedDivider(thickness = 1.dp)
        Spacer(modifier = Modifier.height(24.dp))

        TextMMD(text = "Downloads", fontSize = 22.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(8.dp))
        TwoLineRow(
            title = "Kept offline",
            subtitle = buildString {
                val count = viewModel.offlineTrackIds.size
                append(if (count == 1) "1 song" else "$count songs")
                append(" • ${OfflineManager.formatBytes(viewModel.downloadsSizeBytes())}")
                append(if (viewModel.downloadsOnSdCard) " • SD card" else " • phone storage")
            },
            onClick = onOpenDownloads,
            showDivider = false,
        )

        Spacer(modifier = Modifier.height(24.dp))
        DashedDivider(thickness = 1.dp)
        Spacer(modifier = Modifier.height(24.dp))

        TextMMD(text = "Account", fontSize = 22.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(8.dp))
        TextMMD(
            text = "Logged in to Tidal" +
                (viewModel.sessionCountry?.let { " ($it)" } ?: ""),
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButtonMMD(onClick = { viewModel.logout() }) {
            TextMMD(text = "Log out")
        }

        Spacer(modifier = Modifier.height(24.dp))
        DashedDivider(thickness = 1.dp)
        Spacer(modifier = Modifier.height(24.dp))

        TextMMD(
            text = "MonoMusic streams from the Tidal API. Playback requires an active " +
                "Tidal subscription. Nothing is tracked; your login stays on this phone.",
            fontSize = 13.sp,
        )
    }
}
