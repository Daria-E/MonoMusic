package com.monoapps.monomusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
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
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.text.TextMMD

@Composable
fun SettingsScreen(viewModel: MonoMusicViewModel, onOpenDownloads: () -> Unit) {
    // Fine-grained items: MMD's lazy list swipes a page at a time and snaps
    // to item boundaries, so section-sized items get skipped over. Small
    // row-sized items keep every scroll stop within a row of its target.
    LazyColumnMMD(
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        qualitySection(
            title = "Streaming quality",
            note = "Takes effect from the next song. Higher quality uses more data.",
            selected = viewModel.audioQuality,
            onSelect = { viewModel.selectAudioQuality(it) },
        )
        sectionDivider()
        qualitySection(
            title = "Download quality",
            note = "Used when keeping songs, albums and playlists offline.",
            selected = viewModel.downloadQuality,
            onSelect = { viewModel.selectDownloadQuality(it) },
        )
        sectionDivider()
        item { SectionHeader("Offline") }
        item {
            TwoLineRow(
                title = buildString {
                    val count = viewModel.offlineTrackIds.size
                    append(if (count == 1) "1 song" else "$count songs")
                    append(" • ${OfflineManager.formatBytes(viewModel.offlineSizeBytes)}")
                    append(if (viewModel.downloadsOnSdCard) " • SD card" else " • phone storage")
                },
                subtitle = null,
                onClick = onOpenDownloads,
                showDivider = false,
            )
        }
        sectionDivider()
        item { SectionHeader("Account") }
        item {
            TextMMD(
                text = "Logged in to Tidal" +
                    (viewModel.sessionCountry?.let { " ($it)" } ?: ""),
                fontSize = 16.sp,
            )
        }
        item {
            Column {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButtonMMD(onClick = { viewModel.logout() }) {
                    TextMMD(text = "Log out")
                }
            }
        }
        sectionDivider()
        item {
            TextMMD(
                text = "MonoMusic streams from the Tidal API. Playback requires an active " +
                    "Tidal subscription. Nothing is tracked; your login stays on this phone.",
                fontSize = 13.sp,
            )
        }
    }
}

private fun LazyListScope.qualitySection(
    title: String,
    note: String,
    selected: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
) {
    item { SectionHeader(title) }
    AudioQuality.entries.forEach { quality ->
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(quality) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButtonMMD(
                    selected = selected == quality,
                    onClick = { onSelect(quality) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextMMD(text = quality.label, fontSize = 16.sp)
            }
        }
    }
    item { TextMMD(text = note, fontSize = 13.sp) }
}

private fun LazyListScope.sectionDivider() {
    item {
        Column {
            Spacer(modifier = Modifier.height(24.dp))
            DashedDivider(thickness = 1.dp)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    TextMMD(
        text = title,
        fontSize = 22.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
