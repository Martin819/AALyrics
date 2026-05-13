package cz.aalyrics.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import cz.aalyrics.data.repository.MediaSessionRepository

/**
 * Existence of this listener gives our process the MEDIA_CONTENT_CONTROL
 * privilege required to enumerate active MediaSessions from other apps
 * (Spotify, YouTube Music, …). We don't parse notification text directly —
 * the system MediaSession API already exposes everything we need.
 */
@AndroidEntryPoint
class MediaNotificationListener : NotificationListenerService() {

    @Inject lateinit var mediaSession: MediaSessionRepository

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Once the listener is enabled, the MediaSession reads will succeed.
        mediaSession.start()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        mediaSession.stop()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val self = ComponentName(context, MediaNotificationListener::class.java).flattenToString()
            return flat.split(":").any { it.equals(self, ignoreCase = true) }
        }
    }
}
