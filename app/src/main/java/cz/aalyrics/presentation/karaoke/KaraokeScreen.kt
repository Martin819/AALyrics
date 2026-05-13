package cz.aalyrics.presentation.karaoke

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import cz.aalyrics.R
import cz.aalyrics.domain.LyricsController

@Composable
fun KaraokeScreen(modifier: Modifier = Modifier, vm: KaraokeViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsState()
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Header(ui)
        Spacer(Modifier.height(4.dp))
        DebugStrip(ui)
        Spacer(Modifier.height(8.dp))
        ProgressBar(ui)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.status) {
                LyricsController.UiState.Status.Idle -> Centered(stringResource(R.string.state_idle))
                LyricsController.UiState.Status.Recognising -> Centered(stringResource(R.string.state_recognising))
                LyricsController.UiState.Status.LoadingLyrics -> CenteredLoading()
                LyricsController.UiState.Status.NotFound -> Centered(stringResource(R.string.state_not_found))
                LyricsController.UiState.Status.Error -> Centered("⚠")
                LyricsController.UiState.Status.Ready -> LyricsList(ui)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = { vm.nudge(-5_000L) }) { Text(stringResource(R.string.sync_minus)) }
            OutlinedButton(onClick = { vm.nudge(+5_000L) }) { Text(stringResource(R.string.sync_plus)) }
        }
    }
}

@Composable
private fun Header(ui: LyricsController.UiState) {
    val s = ui.playback.song
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!s?.albumArtUri.isNullOrEmpty()) {
                AsyncImage(
                    model = s!!.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(s?.title ?: stringResource(R.string.state_idle),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(s?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                s?.album?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Tiny diagnostic strip below the header. Visible only in debug builds.
 * Shows raw vs. interpolated position so we can see if MediaSession
 * is reporting reasonable numbers from Spotify / YT Music.
 */
@Composable
private fun DebugStrip(ui: LyricsController.UiState) {
    if (!cz.aalyrics.BuildConfig.DEBUG) return
    // Tick every 250 ms so the interpolated position keeps refreshing
    // even when the activeLineIndex doesn't change.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { delay(250L); tick++ }
    }
    val pb = ui.playback
    val rawPos = pb.positionMs
    // Reading `tick` here makes Compose recompose every 250 ms so the
    // interpolated position is always live.
    val interpPos = run { tick; pb.currentPositionMs() }
    val dur = pb.durationMs
    val firstTs = ui.lyrics.lines.firstOrNull()?.timestampMs
    val lastTs = ui.lyrics.lines.lastOrNull()?.timestampMs
    val pkg = pb.song?.sourcePackage ?: "—"
    Text(
        text = "pkg=$pkg playing=${pb.isPlaying} dur=${dur}ms\n" +
            "rawPos=${rawPos}ms interpPos=${interpPos}ms " +
            "Δupdated=${System.currentTimeMillis() - pb.updatedAt}ms\n" +
            "active=${ui.activeLineIndex}/${ui.lyrics.lines.size} " +
            "lrc=[$firstTs … $lastTs]ms synced=${ui.lyrics.isSynced}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProgressBar(ui: LyricsController.UiState) {
    val dur = ui.playback.durationMs.takeIf { it > 0 } ?: 1L
    val pos = ui.playback.currentPositionMs().coerceIn(0L, dur)
    LinearProgressIndicator(
        progress = { (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(4.dp),
    )
}

@Composable
private fun LyricsList(ui: LyricsController.UiState) {
    val listState = rememberLazyListState()
    val active = ui.activeLineIndex
    LaunchedEffect(active) {
        if (active >= 0) {
            // Center the active line by scrolling slightly above it.
            val target = (active - 2).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(ui.lyrics.lines) { i, line ->
            val isActive = i == active
            val color by animateColorAsState(
                if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                label = "lyric_color",
            )
            Text(
                text = line.text.ifBlank { "♪" },
                color = color,
                fontSize = if (isActive) 26.sp else 20.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.state_loading_lyrics))
        }
    }
}
