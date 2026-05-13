package cz.aalyrics.service.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import dagger.hilt.android.AndroidEntryPoint

/**
 * ===== Android Auto multi-line lyrics workaround =====
 *
 * The standard MediaSession/MediaBrowserService interface that Android Auto
 * uses for media apps renders only a single line of metadata (title /
 * subtitle / description). That's a hard limitation of the Auto media
 * template — you cannot insert a custom view there.
 *
 * To display the full karaoke text (with the active line highlighted) we
 * register this CarAppService — built on the Car App Library
 * (androidx.car.app). The library is the SECOND officially-supported way of
 * delivering UI to Android Auto and it lets us use a [PaneTemplate] /
 * [LongMessageTemplate] which can show many rows of text on screen at once.
 *
 * In Android Auto the user sees both surfaces:
 *  - Standard "Media" entry → KaraokeMediaService (play/pause/skip)
 *  - "AA Lyrics" entry      → THIS service (multi-line lyrics screen)
 *
 * Both surfaces observe the SAME LyricsController state via Hilt, so they
 * are always in sync with the song playing on Spotify / YT Music.
 */
@AndroidEntryPoint
class LyricsCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR // dev convenience; tighten before publishing

    override fun onCreateSession(sessionInfo: SessionInfo): Session = LyricsCarSession()
    override fun onCreateSession(): Session = LyricsCarSession()
}
