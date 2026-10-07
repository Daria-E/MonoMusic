package com.monoapps.monomusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monoapps.monomusic.MonoMusicViewModel
import com.monoapps.monomusic.tidal.TidalTrack
import com.mudita.mmd.components.lazy.LazyColumnMMD

/** Favorite tracks on Tidal; the closest thing to the old "all songs" library. */
@Composable
fun SongsScreen(viewModel: MonoMusicViewModel) {
    val state = viewModel.favoriteTracks
    val tracks = state.data.orEmpty()
    var pickerTrack by remember { mutableStateOf<TidalTrack?>(null) }
    var downloadTrack by remember { mutableStateOf<TidalTrack?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.data == null && state.isLoading -> CenteredMessage("Loading songs…")

            state.data == null && state.error != null ->
                CenteredError(state.error) { viewModel.loadFavoriteTracks() }

            tracks.isEmpty() -> CenteredMessage(
                "No favorite songs yet.\n\nSearch Tidal and long-press a song to add it to your favorites."
            )

            else -> LazyColumnMMD(contentPadding = PaddingValues(16.dp)) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrentlyPlaying = track.id == viewModel.currentTrack?.id,
                        isDownloaded = track.id in viewModel.offlineTrackIds,
                        onClick = { viewModel.playTracks(tracks, index) },
                        menuEntries = listOf(
                            RowMenuEntry("Add to playlist") { pickerTrack = track },
                            RowMenuEntry("Remove from favorites") {
                                viewModel.toggleFavoriteTrack(track)
                            },
                            keepOfflineMenuEntry(viewModel, track) { downloadTrack = it },
                        ),
                        showDivider = index != tracks.lastIndex,
                    )
                }
            }
        }

        if (tracks.isNotEmpty()) {
            OutlinedActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onClick = { viewModel.playTracks(tracks, shuffled = true) },
            ) {
                Icon(imageVector = Icons.Outlined.Shuffle, contentDescription = "Shuffle songs")
            }
        }
    }

    TrackDownloadDialog(
        track = downloadTrack,
        viewModel = viewModel,
        onDismiss = { downloadTrack = null },
    )

    pickerTrack?.let { track ->
        PlaylistPickerDialog(
            playlists = viewModel.playlists.data.orEmpty(),
            onPick = { playlist ->
                viewModel.addToPlaylist(track, playlist)
                pickerTrack = null
            },
            onDismiss = { pickerTrack = null },
        )
    }
}
