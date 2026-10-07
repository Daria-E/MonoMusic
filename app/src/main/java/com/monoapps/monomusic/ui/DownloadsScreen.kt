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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.monoapps.monomusic.MonoMusicViewModel
import com.monoapps.monomusic.offline.OfflineManager
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD

/** Albums and playlists kept offline; works without a connection. */
@Composable
fun DownloadsScreen(viewModel: MonoMusicViewModel) {
    val collections = viewModel.offlineCollections

    if (collections.isEmpty()) {
        CenteredMessage(
            "Nothing kept offline yet.\n\nOpen an album or playlist and tap the " +
                "download icon to keep it on the phone."
        )
        return
    }

    LazyColumnMMD(
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            val pending = viewModel.offlinePendingCount
            val location = if (viewModel.downloadsOnSdCard) "SD card" else "phone storage"
            TextMMD(
                text = if (pending > 0) {
                    "Downloading, $pending songs left • " +
                        "${OfflineManager.formatBytes(viewModel.downloadsSizeBytes())} on $location"
                } else {
                    "${OfflineManager.formatBytes(viewModel.downloadsSizeBytes())} on $location"
                },
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            DashedDivider(thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
        }

        itemsIndexed(collections, key = { _, c -> c.key }) { index, collection ->
            val downloaded = collection.tracks.count { it.id in viewModel.offlineTrackIds }
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { viewModel.playTracks(collection.tracks) },
                    ) {
                        TextMMD(
                            text = collection.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TextMMD(
                            text = buildString {
                                append(
                                    when (collection.type) {
                                        "album" -> "Album"
                                        "playlist" -> "Playlist"
                                        else -> "Song"
                                    }
                                )
                                collection.subtitle?.let { append(" • $it") }
                                append(" • $downloaded of ${collection.tracks.size} songs")
                                append(" • ${collection.quality.name}")
                            },
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        viewModel.unpinCollection(collection.type, collection.id)
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Remove offline copy",
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (index != collections.lastIndex) DashedDivider(thickness = 1.dp)
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButtonMMD(onClick = { viewModel.removeAllDownloads() }) {
                TextMMD(text = "Remove all downloads")
            }
        }
    }
}
