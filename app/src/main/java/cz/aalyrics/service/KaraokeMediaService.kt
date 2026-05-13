package cz.aalyrics.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import cz.aalyrics.R
import cz.aalyrics.domain.LyricsController
import cz.aalyrics.domain.model.PlaybackState as DomainPlayback
import cz.aalyrics.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground MediaLibraryService.
 *
 * Two responsibilities:
 *  1. Keep the app alive in the background while we observe playback from
 *     other apps (Spotify, YT Music). The service shows a foreground
 *     notification with the active lyric line.
 *  2. Expose a MediaSession + media browser tree to Android Auto. The
 *     metadata is mirrored from the LyricsController state, so the standard
 *     Auto media UI shows track info and the CURRENT lyric line as
 *     DISPLAY_DESCRIPTION. (Multi-line view is provided by
 *     LyricsCarAppService — see service/car/.)
 *
 *  Note on the player: we don't actually play audio. The injected
 *  ExoPlayer is in an idle state — it's only there to satisfy the
 *  MediaSession contract.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class KaraokeMediaService : MediaLibraryService() {

    @Inject lateinit var controller: LyricsController

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        controller.start()

        player = ExoPlayer.Builder(this).build().apply {
            playWhenReady = false
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(
            this, player,
            object : MediaLibrarySession.Callback {
                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: LibraryParams?,
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val root = MediaItem.Builder()
                        .setMediaId(ROOT_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(getString(R.string.app_name))
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build(),
                        ).build()
                    return Futures.immediateFuture(LibraryResult.ofItem(root, params))
                }

                override fun onGetChildren(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int, pageSize: Int,
                    params: LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    val cur = controller.state.value
                    val items = mutableListOf<MediaItem>()
                    cur.playback.song?.let { s ->
                        items += MediaItem.Builder()
                            .setMediaId(NOW_PLAYING_ID)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(s.title)
                                    .setArtist(s.artist)
                                    .setAlbumTitle(s.album)
                                    .setDescription(cur.activeLine ?: getString(R.string.car_no_lyrics))
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .build(),
                            ).build()
                    }
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params),
                    )
                }
            },
        )
            .setSessionActivity(pendingIntent)
            .build()

        ensureChannel()
        startForeground(NOTIFICATION_ID, buildIdleNotification())

        // Sync controller → MediaSession metadata so Android Auto sees the active line.
        collectJob = scope.launch {
            controller.state.collectLatest { ui ->
                updateSessionMetadata(ui)
                updateNotification(ui)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        session.release()
        player.release()
        controller.stop()
        super.onDestroy()
    }

    private fun updateSessionMetadata(ui: LyricsController.UiState) {
        val s = ui.playback.song ?: run {
            val empty = MediaItem.Builder()
                .setMediaId(NOW_PLAYING_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.car_no_song))
                        .build(),
                ).build()
            runCatching {
                player.setMediaItem(empty)
                player.prepare()
            }
            return
        }
        val md = MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.artist)
            .setAlbumTitle(s.album)
            .setDescription(ui.activeLine ?: getString(R.string.car_no_lyrics))
            // Subtitle is rendered as second line in some Auto contexts.
            .setSubtitle(ui.activeLine ?: s.artist)
            .setExtras(
                Bundle().apply {
                    putString(EXTRA_FULL_LYRICS, ui.lyrics.lines.joinToString("\n") { it.text })
                    putInt(EXTRA_ACTIVE_LINE, ui.activeLineIndex)
                },
            )
            .build()
        runCatching {
            val item = MediaItem.Builder()
                .setMediaId(NOW_PLAYING_ID)
                .setMediaMetadata(md)
                .build()
            player.setMediaItem(item)
            player.prepare()
        }
    }

    private fun updateNotification(ui: LyricsController.UiState) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildLyricsNotification(ui))
    }

    private fun buildIdleNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_lyrics)
        .setContentTitle(getString(R.string.notification_title_idle))
        .setContentText(getString(R.string.notification_text_idle))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun buildLyricsNotification(ui: LyricsController.UiState) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics)
            .setContentTitle(
                ui.playback.song?.let { "${it.title} – ${it.artist}" }
                    ?: getString(R.string.notification_title_idle),
            )
            .setContentText(ui.activeLine ?: getString(R.string.car_no_lyrics))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ui.activeLine ?: ""))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = getString(R.string.notification_channel_desc)
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    companion object {
        const val ROOT_ID = "root"
        const val NOW_PLAYING_ID = "now_playing"
        const val EXTRA_FULL_LYRICS = "cz.aalyrics.FULL_LYRICS"
        const val EXTRA_ACTIVE_LINE = "cz.aalyrics.ACTIVE_LINE"
        private const val CHANNEL_ID = "karaoke_sync"
        private const val NOTIFICATION_ID = 0xAA
    }
}
