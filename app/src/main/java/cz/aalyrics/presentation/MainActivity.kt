package cz.aalyrics.presentation

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import cz.aalyrics.R
import cz.aalyrics.presentation.karaoke.KaraokeScreen
import cz.aalyrics.presentation.search.ManualSearchScreen
import cz.aalyrics.presentation.settings.SettingsScreen
import cz.aalyrics.service.KaraokeMediaService
import cz.aalyrics.ui.theme.AALyricsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled inline */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Start the foreground service that observes media + powers Android Auto.
        ContextCompat.startForegroundService(
            this, Intent(this, KaraokeMediaService::class.java),
        )

        setContent {
            AALyricsTheme { Root() }
        }
    }
}

private enum class Tab { Karaoke, Search, Settings }

@Composable
private fun Root() {
    var tab by remember { mutableStateOf(Tab.Karaoke) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Karaoke,
                    onClick = { tab = Tab.Karaoke },
                    icon = { Icon(Icons.Filled.LibraryMusic, null) },
                    label = { Text(stringResource(R.string.tab_karaoke)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Search,
                    onClick = { tab = Tab.Search },
                    icon = { Icon(Icons.Filled.Search, null) },
                    label = { Text(stringResource(R.string.tab_search)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { pad ->
        val m = Modifier.fillMaxSize().padding(pad)
        when (tab) {
            Tab.Karaoke -> KaraokeScreen(m)
            Tab.Search -> ManualSearchScreen(m)
            Tab.Settings -> SettingsScreen(m)
        }
    }
}
