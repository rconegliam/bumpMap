package com.rconegliam.bumpmap.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.rconegliam.bumpmap.R
import com.rconegliam.bumpmap.map.MapScreen
import com.rconegliam.bumpmap.recorder.RecordScreen
import com.rconegliam.bumpmap.settings.SettingsScreen

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    MAP(R.string.tab_map, Icons.Filled.Place),
    RECORD(R.string.tab_record, Icons.Filled.PlayArrow),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun BumpMapRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.MAP -> MapScreen(modifier)
            Tab.RECORD -> RecordScreen(modifier)
            Tab.SETTINGS -> SettingsScreen(modifier)
        }
    }
}
