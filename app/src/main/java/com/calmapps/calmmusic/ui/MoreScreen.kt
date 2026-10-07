package com.calmapps.calmmusic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calmapps.calmmusic.Screen

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TwoLineRow(
            title = "Search",
            subtitle = "Find songs, albums, artists and playlists on Tidal",
            onClick = { onNavigate(Screen.Search.route) },
        )
        TwoLineRow(
            title = "Now Playing",
            subtitle = "The current song and queue controls",
            onClick = { onNavigate(Screen.NowPlaying.route) },
        )
        TwoLineRow(
            title = "Settings",
            subtitle = "Streaming quality and account",
            onClick = { onNavigate(Screen.Settings.route) },
            showDivider = false,
        )
    }
}
