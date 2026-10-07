package com.calmapps.calmmusic.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmapps.calmmusic.MonoMusicViewModel
import com.calmapps.calmmusic.tidal.TidalTrack
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD

@Composable
fun AlbumDetailsScreen(viewModel: MonoMusicViewModel) {
    val album = viewModel.selectedAlbum ?: return
    val state = viewModel.albumTracks
    val tracks = state.data.orEmpty()
    var pickerTrack by remember { mutableStateOf<TidalTrack?>(null) }
    val isFavorite = album.id in viewModel.favoriteAlbumIds

    when {
        state.isLoading -> CenteredMessage("Loading album…")

        state.error != null -> CenteredError(state.error) { viewModel.openAlbum(album) }

        tracks.isEmpty() -> CenteredMessage("This album has no playable songs.")

        else -> LazyColumnMMD(
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButtonMMD(onClick = { viewModel.playTracks(tracks) }) {
                        TextMMD(text = "Play")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButtonMMD(onClick = { viewModel.playTracks(tracks, shuffled = true) }) {
                        TextMMD(text = "Shuffle")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.toggleFavoriteAlbum(album) }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite
                            else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove album from favorites"
                            else "Add album to favorites",
                        )
                    }
                }
                album.audioQuality?.let { quality ->
                    TextMMD(text = quality, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                DashedDivider(thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))
            }

            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                val isTrackFavorite = track.id in viewModel.favoriteTrackIds
                TrackRow(
                    track = track,
                    isCurrentlyPlaying = track.id == viewModel.currentTrack?.id,
                    onClick = { viewModel.playTracks(tracks, index) },
                    showTrackNumber = true,
                    menuEntries = listOf(
                        RowMenuEntry(
                            if (isTrackFavorite) "Remove from favorites" else "Add to favorites"
                        ) { viewModel.toggleFavoriteTrack(track) },
                        RowMenuEntry("Add to playlist") { pickerTrack = track },
                    ),
                    showDivider = index != tracks.lastIndex,
                )
            }
        }
    }

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
