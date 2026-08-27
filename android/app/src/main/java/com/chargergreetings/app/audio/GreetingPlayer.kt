package com.chargergreetings.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import com.chargergreetings.app.R
import com.chargergreetings.app.core.Greeting
import com.chargergreetings.app.util.Diagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Plays a greeting clip from `res/raw`.
 *
 * Design notes:
 *
 *  * **Audio focus.** A transient, ducking request is taken for the ~1.5 s the
 *    greeting lasts. Music the user is listening to dips rather than stopping,
 *    and podcast apps do not lose their place. Focus is always abandoned, even
 *    on failure.
 *
 *  * **Usage type.** `USAGE_ASSISTANCE_SONIFICATION` on the media stream. This
 *    is a UI sound, not music and not a notification: it should not appear as
 *    "now playing", should not be routed to a call, and should not be silenced
 *    by the ringer switch — the silent-mode preference handles that decision
 *    explicitly instead of leaving it to stream routing.
 *
 *  * **Volume.** Applied per-player via a squared curve, so the slider tracks
 *    perceived loudness instead of doing nothing until the top 20 %. The
 *    device's own media volume is never touched.
 */
class GreetingPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var focusRequest: AudioFocusRequest? = null

    // Guards the currently-playing player so stop() and a second event cannot
    // race, and so two greetings can never overlap.
    private val activeLock = Any()
    private var activePlayer: MediaPlayer? = null

    /**
     * Plays [greeting] and suspends until it finishes.
     *
     * @return null on success, or a short human-readable problem description.
     */
    suspend fun play(greeting: Greeting, volumePercent: Int): String? {
        // A custom sound wins only if the user picked one AND it still opens.
        // When it has gone missing we fall back to the bundled clip instead of
        // going silent: a greeting the user can hear beats a correct error, and
        // the missing file is surfaced separately in the UI and diagnostics.
        val choice = SoundLibrary.choiceFor(appContext, greeting)
        if (choice.isCustom && !choice.available) {
            Diagnostics.log(
                appContext,
                "Custom sound unavailable, falling back to built-in: " + choice.label
            )
        }
        val customUri = if (choice.available) choice.uri else null

        val resId = when (greeting) {
            Greeting.CONNECTED -> R.raw.power_connected
            Greeting.DISCONNECTED -> R.raw.power_disconnected
        }

        var player: MediaPlayer? = null
        try {
            requestFocus()

            // Held in a val as well as the outer var: Kotlin will not smart-cast
            // a local var that is captured by the listener lambdas below.
            val created = createPlayer(customUri, resId)
                ?: return "the greeting audio could not be opened"
            player = created
            // Tracked so a preview can be stopped on demand, and so a second
            // event can never leave two clips overlapping.
            synchronized(activeLock) { activePlayer = created }

            val gain = volumeToGain(volumePercent)
            created.setVolume(gain, gain)

            return suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)

                fun finish(result: String?) {
                    if (finished.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                created.setOnCompletionListener { finish(null) }
                created.setOnErrorListener { _, what, extra ->
                    Diagnostics.log(appContext, "MediaPlayer error what=$what extra=$extra")
                    finish("the audio system reported an error")
                    true
                }

                continuation.invokeOnCancellation { finish("cancelled") }

                try {
                    created.start()
                } catch (e: IllegalStateException) {
                    Diagnostics.log(appContext, "MediaPlayer.start failed: ${e.message}")
                    finish("the greeting could not be started")
                }
            }
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Playback failed: ${e.message}")
            return "playback failed: ${e.message}"
        } finally {
            try {
                player?.setOnCompletionListener(null)
                player?.setOnErrorListener(null)
                player?.release()
            } catch (e: Exception) {
                // Releasing a player that already errored can throw.
                Diagnostics.log(appContext, "Player release threw: " + e.message)
            }
            synchronized(activeLock) {
                if (activePlayer === player) activePlayer = null
            }
            abandonFocus()
        }
    }

    /**
     * Builds a MediaPlayer for the custom URI when there is one, falling back to
     * the bundled raw resource. Returns null if neither can be opened.
     */
    private fun createPlayer(customUri: android.net.Uri?, resId: Int): MediaPlayer? {
        if (customUri != null) {
            try {
                return MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(appContext, customUri)
                    prepare()
                }
            } catch (e: Exception) {
                // Covers a file deleted between the availability check and here,
                // an unsupported codec, and a revoked permission grant.
                Diagnostics.log(
                    appContext,
                    "Custom sound failed to open (" + e.message + "); using built-in"
                )
            }
        }
        return MediaPlayer.create(appContext, resId, attributes, generateSessionId())
    }

    /**
     * Stops a preview immediately. Used by the "Stop" button; safe to call when
     * nothing is playing.
     */
    fun stop() {
        synchronized(activeLock) {
            val current = activePlayer ?: return
            try {
                if (current.isPlaying) current.stop()
            } catch (e: IllegalStateException) {
                Diagnostics.log(appContext, "Stop on a finished player: " + e.message)
            }
            activePlayer = null
        }
        abandonFocus()
    }

    /**
     * Perceived-loudness curve. A linear slider feels broken because loudness is
     * roughly logarithmic; squaring the fraction makes the middle of the slider
     * sound like the middle.
     */
    private fun volumeToGain(percent: Int): Float {
        val clamped = percent.coerceIn(0, 100) / 100f
        return clamped.toDouble().pow(2.0).toFloat()
    }

    private fun generateSessionId(): Int =
        audioManager?.generateAudioSessionId() ?: AudioManager.AUDIO_SESSION_ID_GENERATE

    private fun requestFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attributes)
                    // We never want the system to pause us mid-greeting; the clip
                    // is shorter than any sensible pause/resume handshake.
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Audio focus request failed: ${e.message}")
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { manager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Abandoning audio focus failed: ${e.message}")
        }
    }
}
