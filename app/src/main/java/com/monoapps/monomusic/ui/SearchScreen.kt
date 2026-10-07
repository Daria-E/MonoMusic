package com.monoapps.monomusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monoapps.monomusic.MonoMusicViewModel
import com.monoapps.monomusic.tidal.TidalAlbum
import com.monoapps.monomusic.tidal.TidalArtist
import com.monoapps.monomusic.tidal.TidalPlaylist
import com.monoapps.monomusic.tidal.TidalTrack
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.tabs.PrimaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.text.TextMMD

private val searchTabs = listOf("Songs", "Albums", "Artists", "Playlists")

@androidx.compose.runtime.Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun SearchScreen(
    viewModel: MonoMusicViewModel,
    onOpenAlbum: (TidalAlbum) -> Unit,
    onOpenArtist: (TidalArtist) -> Unit,
    onOpenPlaylist: (TidalPlaylist) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var pickerTrack by remember { mutableStateOf<TidalTrack?>(null) }
    val results = viewModel.searchResults

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRowMMD(selectedTabIndex = tab) {
            searchTabs.forEachIndexed { index, title ->
                TabMMD(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { TextMMD(text = title) },
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                viewModel.isSearching -> CenteredMessage("Searching…")

                viewModel.searchError != null ->
                    CenteredError(viewModel.searchError!!) { viewModel.performSearch() }

                results == null -> CenteredMessage(
                    "Search Tidal for songs, albums, artists and playlists."
                )

                else -> when (tab) {
                    0 -> SearchTrackResults(viewModel, results.tracks) { pickerTrack = it }
                    1 -> SearchList(results.albums, { it.id }, "No albums found.") { album, last ->
                        AlbumRow(album, onClick = { onOpenAlbum(album) }, showDivider = !last)
                    }
                    2 -> SearchList(results.artists, { it.id }, "No artists found.") { artist, last ->
                        ArtistRow(artist, onClick = { onOpenArtist(artist) }, showDivider = !last)
                    }
                    else -> SearchList(results.playlists, { it.uuid }, "No playlists found.") { playlist, last ->
                        PlaylistRow(playlist, onClick = { onOpenPlaylist(playlist) }, showDivider = !last)
                    }
                }
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

@Composable
private fun SearchTrackResults(
    viewModel: MonoMusicViewModel,
    tracks: List<TidalTrack>,
    onAddToPlaylist: (TidalTrack) -> Unit,
) {
    if (tracks.isEmpty()) {
        CenteredMessage("No songs found.")
        return
    }
    LazyColumnMMD(contentPadding = PaddingValues(16.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            val isFavorite = track.id in viewModel.favoriteTrackIds
            TrackRow(
                track = track,
                isCurrentlyPlaying = track.id == viewModel.currentTrack?.id,
                onClick = { viewModel.playTracks(tracks, index) },
                menuEntries = listOf(
                    RowMenuEntry(
                        if (isFavorite) "Remove from favorites" else "Add to favorites"
                    ) { viewModel.toggleFavoriteTrack(track) },
                    RowMenuEntry("Add to playlist") { onAddToPlaylist(track) },
                ),
                showDivider = index != tracks.lastIndex,
            )
        }
    }
}

@Composable
private fun <T> SearchList(
    items: List<T>,
    key: (T) -> Any,
    emptyText: String,
    row: @Composable (T, Boolean) -> Unit,
) {
    if (items.isEmpty()) {
        CenteredMessage(emptyText)
        return
    }
    LazyColumnMMD(contentPadding = PaddingValues(16.dp)) {
        itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            row(item, index == items.lastIndex)
        }
    }
}
