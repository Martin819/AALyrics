package cz.aalyrics.service.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

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
 * (androidx.car.app). The library lets us use [PaneTemplate] /
 * [LongMessageTemplate] which can show many rows of text on screen at
 * once.
 *
 * The session resolves the LyricsController via Hilt's EntryPoint at first
 * onCreateScreen() — no @AndroidEntryPoint here because we don't inject
 * directly into the service, and adding it has historically caused some
 * AA hosts to skip the binding.
 */
class LyricsCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR // dev convenience; tighten before publishing

    override fun onCreateSession(sessionInfo: SessionInfo): Session = LyricsCarSession()
    override fun onCreateSession(): Session = LyricsCarSession()
}

