package cz.aalyrics.service.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import dagger.hilt.android.EntryPointAccessors
import cz.aalyrics.di.CarEntryPoint

class LyricsCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen = try {
        val entryPoint = EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            CarEntryPoint::class.java,
        )
        // Ensure the controller is running so we have something to show.
        entryPoint.lyricsController().start()
        LyricsCarScreen(carContext, entryPoint.lyricsController())
    } catch (t: Throwable) {
        // Never crash the binding — AA hosts have been observed to delist
        // apps whose Session creation throws. Surface a placeholder screen
        // instead so the user gets some feedback.
        Log.e("LyricsCarSession", "Failed to wire LyricsController", t)
        FallbackScreen(t.message ?: "unknown error")
    }

    private inner class FallbackScreen(private val msg: String) : Screen(carContext) {
        override fun onGetTemplate(): Template =
            MessageTemplate.Builder("AA Lyrics — init failed:\n$msg")
                .setTitle("AA Lyrics")
                .setHeaderAction(Action.APP_ICON)
                .build()
    }
}
