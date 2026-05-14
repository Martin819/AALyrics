package cz.aalyrics.data.repository

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as SysPlaybackState
import android.os.Handler
import android.os.Looper
import cz.aalyrics.domain.model.PlaybackState
import cz.aalyrics.domain.model.Song
import cz.aalyrics.service.MediaNotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads playback state from the system MediaSessionManager. Relies on the
 * NotificationListenerService being enabled — that grants us
 * MEDIA_CONTENT_CONTROL implicitly so we may list active sessions of
 * any package (Spotify, YouTube Music, …).
 *
 * Emits a [PlaybackState] every time the active controller, its metadata
 * or its playback state changes. We DO NOT capture audio — only metadata.
 */
@Singleton
class MediaSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> get() = _state

    private val sessionManager: MediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val listenerComponent =
        ComponentName(context, MediaNotificationListener::class.java)

    private var registered = false
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        rebindControllers(controllers.orEmpty())
        publishCurrent()
    }

    /** Idempotently subscribe. Safe to call from any service start. */
    @Synchronized
    fun start() {
        if (registered) return
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsListener, listenerComponent, handler,
            )
            registered = true
            rebindControllers(sessionManager.getActiveSessions(listenerComponent))
            publishCurrent()
        } catch (se: SecurityException) {
            // Listener not yet enabled — caller should prompt user.
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllerCallbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        controllerCallbacks.clear()
        registered = false
    }

    private fun rebindControllers(controllers: List<MediaController>) {
        // unregister old
        val toRemove = controllerCallbacks.keys.filter { it !in controllers }
        toRemove.forEach { c ->
            controllerCallbacks.remove(c)?.let { cb -> runCatching { c.unregisterCallback(cb) } }
        }
        // register new
        controllers.filter { it !in controllerCallbacks }.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = publishCurrent()
                override fun onPlaybackStateChanged(state: SysPlaybackState?) = publishCurrent()
                override fun onSessionDestroyed() {
                    controllerCallbacks.remove(c)
                    publishCurrent()
                }
            }
            runCatching { c.registerCallback(cb, handler) }.onSuccess {
                controllerCallbacks[c] = cb
            }
        }
    }

    private fun publishCurrent() {
        // EXCLUDE our own session — KaraokeMediaService publishes a stub
        // MediaSession that we'd otherwise read back from, causing a
        // feedback loop with position == 0.
        val ownPkg = context.packageName
        val candidates = controllerCallbacks.keys.filter { it.packageName != ownPkg }
        val active = pickActive(candidates)
        if (active == null) {
            _state.value = PlaybackState.Idle
            return
        }
        val meta = active.metadata
        val state = active.playbackState
        val song = meta?.let {
            Song(
                title = it.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = it.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    ?: it.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                album = it.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = it.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION),
                albumArtUri = it.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: it.getString(android.media.MediaMetadata.METADATA_KEY_ART_URI)
                    ?: it.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
                sourcePackage = active.packageName,
            )
        }?.takeIf { it.isValid }

        // PlaybackState.lastPositionUpdateTime is reported in SystemClock.elapsedRealtime()
        // units (since boot). Our domain PlaybackState.updatedAt is wall-clock
        // (System.currentTimeMillis()). Convert here so the domain stays Android-free.
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val updatedAtWall = if (state != null && state.lastPositionUpdateTime > 0L) {
            nowWall - (nowElapsed - state.lastPositionUpdateTime).coerceAtLeast(0L)
        } else {
            nowWall
        }

        _state.value = PlaybackState(
            song = song,
            positionMs = (state?.position ?: 0L).coerceAtLeast(0L),
            isPlaying = state?.state == SysPlaybackState.STATE_PLAYING,
            durationMs = song?.durationMs ?: 0L,
            updatedAt = updatedAtWall,
        )
    }

    // ─── Command forwarding ──────────────────────────────────────────────
    // Our MediaSession (KaraokeMediaService) becomes the "currently active"
    // one as soon as the user taps our app in Android Auto, so steering-
    // wheel media keys and AA's transport controls flow into us — not the
    // real music app. These helpers re-dispatch the command to whichever
    // foreign controller is actually playing, so play/pause/skip on the
    // wheel keep working with Spotify / YT Music.
    private fun activeForeignController(): MediaController? {
        val ownPkg = context.packageName
        val candidates = controllerCallbacks.keys.filter { it.packageName != ownPkg }
        return pickActive(candidates)
    }

    fun dispatchPlay() {
        runCatching { activeForeignController()?.transportControls?.play() }
    }

    fun dispatchPause() {
        runCatching { activeForeignController()?.transportControls?.pause() }
    }

    fun dispatchNext() {
        runCatching { activeForeignController()?.transportControls?.skipToNext() }
    }

    fun dispatchPrevious() {
        runCatching { activeForeignController()?.transportControls?.skipToPrevious() }
    }

    fun dispatchSeekTo(positionMs: Long) {
        runCatching { activeForeignController()?.transportControls?.seekTo(positionMs) }
    }

    private fun pickActive(controllers: List<MediaController>): MediaController? {
        if (controllers.isEmpty()) return null
        // 1) Actively playing wins.
        controllers.firstOrNull { it.playbackState?.state == SysPlaybackState.STATE_PLAYING }
            ?.let { return it }
        // 2) Buffering/connecting (about to play).
        controllers.firstOrNull {
            it.playbackState?.state in setOf(
                SysPlaybackState.STATE_BUFFERING,
                SysPlaybackState.STATE_CONNECTING,
            )
        }?.let { return it }
        // 3) Paused with usable metadata — most recently updated.
        return controllers
            .filter {
                val s = it.playbackState?.state ?: SysPlaybackState.STATE_NONE
                it.metadata != null && s != SysPlaybackState.STATE_NONE &&
                    s != SysPlaybackState.STATE_STOPPED && s != SysPlaybackState.STATE_ERROR
            }
            .maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
    }
}
