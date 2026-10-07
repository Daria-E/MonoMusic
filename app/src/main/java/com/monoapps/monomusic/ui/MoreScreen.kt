package com.monoapps.monomusic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monoapps.monomusic.Screen

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TwoLineRow(
            title = "Settings",
            subtitle = "Streaming quality and account",
            onClick = { onNavigate(Screen.Settings.route) },
            showDivider = false,
        )
    }
}
