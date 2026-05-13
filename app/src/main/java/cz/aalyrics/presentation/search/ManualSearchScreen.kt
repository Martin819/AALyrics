package cz.aalyrics.presentation.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cz.aalyrics.R

@Composable
fun ManualSearchScreen(modifier: Modifier = Modifier, vm: ManualSearchViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsState()
    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = ui.artist, onValueChange = vm::setArtist,
            label = { Text(stringResource(R.string.search_artist)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ui.title, onValueChange = vm::setTitle,
            label = { Text(stringResource(R.string.search_title)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = vm::search, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.search_action))
        }
        Spacer(Modifier.height(16.dp))
        when (ui.status) {
            ManualSearchViewModel.UiState.Status.Idle -> Unit
            ManualSearchViewModel.UiState.Status.Loading -> CircularProgressIndicator()
            ManualSearchViewModel.UiState.Status.NotFound ->
                Text(stringResource(R.string.state_not_found))
            ManualSearchViewModel.UiState.Status.Error -> Text("⚠")
            ManualSearchViewModel.UiState.Status.Found -> Text(
                ui.lyricsPreview.orEmpty(),
            )
        }
    }
}
