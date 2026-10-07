package com.monoapps.monomusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monoapps.monomusic.MonoMusicViewModel
import com.monoapps.monomusic.offline.OfflineCollection
import com.monoapps.monomusic.offline.OfflineManager
import com.monoapps.monomusic.tidal.AudioQuality
import com.monoapps.monomusic.tidal.TidalTrack
import com.mudita.mmd.components.text.TextMMD

/** Quality picker + pin for a single song; renders nothing while [track] is null. */
@Composable
fun TrackDownloadDialog(
    track: TidalTrack?,
    viewModel: MonoMusicViewModel,
    onDismiss: () -> Unit,
) {
    if (track == null) return
    DownloadQualityDialog(
        totalSeconds = track.durationSec.toLong(),
        onPick = { quality ->
            viewModel.pinCollection(
                OfflineCollection(
                    type = "track",
                    id = track.id.toString(),
                    title = track.title,
                    subtitle = track.artistName.takeIf { it.isNotBlank() },
                    quality = quality,
                    tracks = listOf(track),
                )
            )
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** A ready-made long-press menu entry for keeping a single song offline. */
fun keepOfflineMenuEntry(
    viewModel: MonoMusicViewModel,
    track: TidalTrack,
    onPickQuality: (TidalTrack) -> Unit,
): RowMenuEntry {
    val id = track.id.toString()
    return if (viewModel.isPinned("track", id)) {
        RowMenuEntry("Remove offline copy") { viewModel.unpinCollection("track", id) }
    } else {
        RowMenuEntry("Keep offline") { onPickQuality(track) }
    }
}

/** Quality picker shown before pinning something offline, with a size estimate. */
@Composable
fun DownloadQualityDialog(
    totalSeconds: Long,
    onPick: (AudioQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    MonoDialog(title = "Keep offline", onDismissRequest = onDismiss) {
        TextMMD(text = "Choose the download quality:", fontSize = 15.sp)
        Spacer(modifier = Modifier.height(8.dp))
        AudioQuality.entries.forEach { quality ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(quality) }
                    .padding(vertical = 10.dp),
            ) {
                TextMMD(text = quality.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                TextMMD(
                    text = "about " + OfflineManager.formatBytes(
                        OfflineManager.estimatedBytes(totalSeconds, quality)
                    ),
                    fontSize = 14.sp,
                )
            }
            DashedDivider(thickness = 1.dp)
        }
    }
}
