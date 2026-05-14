package cz.aalyrics.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import cz.aalyrics.R
import cz.aalyrics.domain.LyricsController
import cz.aalyrics.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Foreground media service exposed to Android Auto.
 *
 *  - Wraps a silent [ExoPlayer] (handleAudioFocus = false) so the AA host can
 *    issue play/pause without ever stealing focus from the real music app
 *    (Spotify, YT Music). Tap on our entry in AA never produces "Could not
 *    load your selection" anymore.
 *  - Exposes the active song and a sliding window of lyric lines as a
 *    [MediaLibraryService] browse tree. AA renders the children of the root
 *    as a scrollable list, so the user effectively sees multiple lyric
 *    lines in the car — the active line is prefixed with "▶ ".
 *  - Mirrors the active line into the player's media-item metadata, so the
 *    AA "Now Playing" card also reflects the current line.
 *
 *  The Car App Library "templated" path was attempted earlier but consumer
 *  AA 16.x on Pixel + Android 16 silently filters sideloaded templated apps
 *  out of the launcher regardless of category / permissions / library
 *  version. The media browse approach is the only AA surface we can
 *  reliably get visibility for.
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

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(SilenceOnlyMediaSourceFactory())
            // handleAudioFocus = false: we play silence, never want to duck
            // or pause the real music app.
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus */ false)
            .setHandleAudioBecomingNoisy(false)
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ALL
                setMediaItem(buildItem(NOW_PLAYING_ID, idleMetadata()))
                prepare()
            }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(pendingIntent)
            .build()

        ensureChannel()
        startForeground(NOTIFICATION_ID, buildIdleNotification())

        // Push UI state changes to both the browse tree (notifyChildrenChanged)
        // and the player's metadata (replaceMediaItem) so AA's two surfaces
        // — list view and Now Playing card — stay in sync.
        collectJob = controller.state
            .map { snapshot ->
                Snapshot(
                    cacheKey = snapshot.playback.song?.cacheKey(),
                    activeLine = snapshot.activeLine,
                    activeIndex = snapshot.activeLineIndex,
                    songTitle = snapshot.playback.song?.title,
                    songArtist = snapshot.playback.song?.artist,
                    totalLines = snapshot.lyrics.lines.size,
                )
            }
            .distinctUntilChanged()
            .onEach(::onSnapshot)
            .launchIn(scope)
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

    // ─── Snapshot of state we care about for AA updates ──────────────────
    private data class Snapshot(
        val cacheKey: String?,
        val activeLine: String?,
        val activeIndex: Int,
        val songTitle: String?,
        val songArtist: String?,
        val totalLines: Int,
    )

    private fun onSnapshot(s: Snapshot) {
        // 1) update player's current item metadata → Now Playing card
        val title = when {
            s.activeLine != null -> "▶ ${s.activeLine}"
            s.songTitle != null -> s.songTitle
            else -> getString(R.string.car_no_song)
        }
        val subtitle = listOfNotNull(s.songTitle, s.songArtist).joinToString(" — ")
            .ifBlank { getString(R.string.app_name) }
        val md = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtist(s.songArtist)
            .setAlbumTitle(s.songTitle)
            .setDescription(s.activeLine ?: getString(R.string.car_no_lyrics))
            .build()
        runCatching {
            // Same URI → ExoPlayer treats this as a metadata-only update.
            player.replaceMediaItem(0, buildItem(NOW_PLAYING_ID, md))
        }

        // 2) notify AA the browse tree changed → re-renders the list
        runCatching {
            session.notifyChildrenChanged(ROOT_ID, max(s.totalLines, 1), null)
        }

        // 3) phone-side foreground notification
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildLyricsNotification(s))
    }

    // ─── Browse tree ─────────────────────────────────────────────────────
    private inner class LibraryCallback : MediaLibrarySession.Callback {
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
            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.of(), params),
                )
            }
            val items = currentChildren()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params),
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val items = currentChildren()
            val match = items.firstOrNull { it.mediaId == mediaId }
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(match, null))
        }
    }

    /**
     * Current browse-tree children: a sliding window of lyric lines around
     * the active one. Each line is exposed as a playable item that resolves
     * (via SilenceOnlyMediaSourceFactory) to a silent media source — so
     * tapping any row in AA never produces a playback error.
     *
     * AA's media browse list is limited by driver-distraction guidelines to
     * a small handful of items per screen; we cap at WINDOW_SIZE around the
     * active line so the user always sees the now-playing context.
     */
    private fun currentChildren(): List<MediaItem> {
        val ui = controller.state.value
        val lines = ui.lyrics.lines
        if (lines.isEmpty()) {
            val song = ui.playback.song
            val title = song?.let { "${it.title} — ${it.artist}" }
                ?: getString(R.string.car_no_song)
            val md = MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(getString(R.string.car_no_lyrics))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
            return listOf(buildItem("placeholder", md))
        }
        val active = ui.activeLineIndex.coerceAtLeast(0)
        // Show 2 lines above + active + 5 below = 8 items.
        val from = max(0, active - 2)
        val to = min(lines.size, from + WINDOW_SIZE)
        return (from until to).map { i ->
            val line = lines[i]
            val display = if (i == active) "▶ ${line.text}" else line.text
            val md = MediaMetadata.Builder()
                .setTitle(display.ifBlank { "♪" })
                .setSubtitle(ui.playback.song?.let { "${it.title} — ${it.artist}" })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
            buildItem("line:$i", md)
        }
    }

    // ─── MediaItem / silence plumbing ────────────────────────────────────
    private fun buildItem(id: String, metadata: MediaMetadata): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            // A fixed URI: ExoPlayer treats replaceMediaItem with the same
            // URI as a metadata-only update (no source rebuild).
            .setUri(SILENCE_URI)
            .setMediaMetadata(metadata)
            .build()

    private fun idleMetadata() = MediaMetadata.Builder()
        .setTitle(getString(R.string.app_name))
        .setSubtitle(getString(R.string.car_no_song))
        .build()

    /**
     * Resolves every requested MediaItem to a silent media source.
     *
     * Media3 1.4 keeps the (durationUs, MediaItem) SilenceMediaSource
     * constructor private, so we wrap the public single-arg one in a
     * [WrappingMediaSource] and override [getMediaItem] to return the
     * caller's MediaItem (with our metadata). ExoPlayer + MediaSession
     * read the returned MediaItem when populating Now Playing.
     * (WrappingMediaSource was added in Media3 1.1 and is the predecessor
     * of ForwardingMediaSource, which only landed in 1.5.)
     */
    private class SilenceOnlyMediaSourceFactory : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory = this
        override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory = this
        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            object : WrappingMediaSource(SilenceMediaSource(SILENCE_DURATION_US)) {
                override fun getMediaItem(): MediaItem = mediaItem
            }
    }

    // ─── Notification ────────────────────────────────────────────────────
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

    private fun buildLyricsNotification(s: Snapshot) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics)
            .setContentTitle(
                listOfNotNull(s.songTitle, s.songArtist).joinToString(" – ")
                    .ifBlank { getString(R.string.notification_title_idle) },
            )
            .setContentText(s.activeLine ?: getString(R.string.car_no_lyrics))
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.activeLine ?: ""))
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
        private const val CHANNEL_ID = "karaoke_sync"
        private const val NOTIFICATION_ID = 0xAA
        private const val WINDOW_SIZE = 8
        private const val SILENCE_DURATION_US = 60_000_000L  // 60 s, looped
        private val SILENCE_URI: Uri = Uri.parse("silence://now")
    }
}
