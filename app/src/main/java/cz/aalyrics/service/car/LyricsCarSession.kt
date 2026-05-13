package cz.aalyrics.service.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import dagger.hilt.android.EntryPointAccessors
import cz.aalyrics.di.CarEntryPoint

class LyricsCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val entryPoint = EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            CarEntryPoint::class.java,
        )
        // Ensure the controller is running so we have something to show.
        entryPoint.lyricsController().start()
        return LyricsCarScreen(carContext, entryPoint.lyricsController())
    }
}
