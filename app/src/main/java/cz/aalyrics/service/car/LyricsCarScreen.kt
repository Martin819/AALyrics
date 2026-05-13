package cz.aalyrics.service.car

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cz.aalyrics.R
import cz.aalyrics.domain.LyricsController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Multi-line karaoke screen for Android Auto.
 *
 * The Car App Library renders one of two templates depending on payload size:
 *  - [PaneTemplate]    — preferred when the lyrics fit; we emit ~5 surrounding
 *                        lines as separate Rows so the active one can be
 *                        bolded and coloured.
 *  - [LongMessageTemplate] — fallback for very long content; renders a
 *                        scrolling block of text on the head unit.
 *
 * The screen calls [invalidate] whenever the LyricsController state changes,
 * causing the host to re-fetch the template. Auto's UX guidelines throttle
 * updates so we stick with refreshing only on active-line changes.
 */
class LyricsCarScreen(
    carContext: CarContext,
    private val controller: LyricsController,
) : Screen(carContext), DefaultLifecycleObserver {

    private var observer: Job? = null
    private var lastSnapshot: LyricsController.UiState = controller.state.value

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        observer = owner.lifecycleScope.launch {
            controller.state.collectLatest { s ->
                // Refresh only when the active line index OR the song changes.
                if (s.activeLineIndex != lastSnapshot.activeLineIndex ||
                    s.playback.song?.cacheKey() != lastSnapshot.playback.song?.cacheKey() ||
                    s.status != lastSnapshot.status
                ) {
                    lastSnapshot = s
                    invalidate()
                }
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        observer?.cancel()
    }

    override fun onGetTemplate(): Template {
        val ui = controller.state.value
        val song = ui.playback.song

        if (song == null) {
            return MessageTemplate.Builder(carContext.getString(R.string.car_no_song))
                .setTitle(carContext.getString(R.string.car_app_name))
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val lines = ui.lyrics.lines
        if (lines.isEmpty()) {
            return MessageTemplate.Builder(carContext.getString(R.string.car_no_lyrics))
                .setTitle("${song.title} — ${song.artist}")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        // ── Strategy: show up to N lines around the active one ───────────────
        val window = 6
        val active = ui.activeLineIndex.coerceAtLeast(0)
        val from = (active - 1).coerceAtLeast(0)
        val to = (from + window).coerceAtMost(lines.size)

        val pane = Pane.Builder()
        for (i in from until to) {
            val text = lines[i].text.ifBlank { "♪" }
            val styled = SpannableString(text).apply {
                if (i == active) {
                    setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    setSpan(
                        ForegroundColorSpan(0xFF7C4DFF.toInt()),
                        0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
                    )
                }
            }
            pane.addRow(
                Row.Builder()
                    .setTitle(styled)
                    .build(),
            )
        }
        pane.addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.sync_minus))
                .setOnClickListener { controller.bumpOffset(-5_000L); invalidate() }
                .build(),
        )
        pane.addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.sync_plus))
                .setOnClickListener { controller.bumpOffset(+5_000L); invalidate() }
                .build(),
        )

        // Long lyrics? Fall back to LongMessageTemplate so the full text scrolls.
        if (lines.size > 40) {
            val full = buildString {
                lines.forEachIndexed { i, l ->
                    if (i == active) append("▶ ")
                    appendLine(l.text)
                }
            }
            return LongMessageTemplate.Builder(full)
                .setTitle("${song.title} — ${song.artist}")
                .setHeaderAction(Action.BACK)
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.sync_minus))
                        .setOnClickListener { controller.bumpOffset(-5_000L); invalidate() }
                        .build(),
                )
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.sync_plus))
                        .setOnClickListener { controller.bumpOffset(+5_000L); invalidate() }
                        .build(),
                )
                .build()
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle("${song.title} — ${song.artist}")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
