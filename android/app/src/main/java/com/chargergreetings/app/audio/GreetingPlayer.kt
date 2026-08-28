package com.chargergreetings.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import com.chargergreetings.app.core.PlaybackLimit
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.core.SoundSlot
import com.chargergreetings.app.util.Diagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Plays one slot's sound.
 *
 * Design notes:
 *
 *  * **Exactly one sound at a time, process-wide.** The active player lives in
 *    a companion-object field, not an instance field, because previews come
 *    from the ViewModel while charging events come from the service -- two
 *    different instances that must still never overlap. Starting anything stops
 *    and releases whatever was playing.
 *
 *  * **Audio focus.** Transient-may-duck for the ~1 s a clip lasts, so music
 *    dips rather than stopping and podcasts keep their place. Always abandoned.
 *
 *  * **Usage type.** USAGE_ASSISTANCE_SONIFICATION: it is a UI sound, not
 *    music and not a notification. Silent-mode behaviour is then an explicit
 *    user setting rather than an accident of stream routing.
 *
 *  * **Volume.** Applied per-player on a squared curve so the slider tracks
 *    perceived loudness. The device's own media volume is never touched.
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

    /**
     * Plays the sound configured for [slot] and suspends until it finishes,
     * is cut short by the duration limit, or fails.
     *
     * @return null on success, or a short human-readable problem.
     */
    suspend fun play(slot: SoundSlot): String? {
        val settings = SettingsRepository(appContext)
        val config = settings.slotConfig(slot)
        return play(slot, config.volumePercent, config.limit)
    }

    suspend fun play(slot: SoundSlot, volumePercent: Int, limit: PlaybackLimit): String? {
        val settings = SettingsRepository(appContext)
        val resolved = SoundCatalog.resolve(appContext, slot, settings.slotSource(slot))

        if (!resolved.available) {
            Diagnostics.log(
                appContext,
                "Sound for ${slot.storageKey} unavailable (${resolved.problem}); using built-in"
            )
        }

        // Stop anything already playing before taking focus, so a rapid double
        // tap can never leave two clips running.
        stopActive()
        requestFocus()

        var player: MediaPlayer? = null
        try {
            val created = createPlayer(resolved, slot)
                ?: return "the sound could not be opened"
            player = created
            setActive(created)

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

                // Registered alongside the player so that if something else
                // (a charging event interrupting a preview) stops this player,
                // this coroutine is resumed rather than left suspended forever.
                // MediaPlayer.stop() does not fire onCompletion, so without
                // this the preview would hang and the UI would stay stuck on
                // "playing".
                setActiveFinisher(created, finish = { finish("interrupted") })

                continuation.invokeOnCancellation { finish("cancelled") }

                try {
                    created.start()
                    if (limit.isLimited) scheduleStop(created, limit, ::finish)
                } catch (e: IllegalStateException) {
                    Diagnostics.log(appContext, "MediaPlayer.start failed: ${e.message}")
                    finish("the sound could not be started")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Playback failed: ${e.message}")
            return "playback failed: ${e.message}"
        } finally {
            release(player)
            abandonFocus()
        }
    }

    /**
     * Stops the clip after the configured limit.
     *
     * A plain Handler on the main looper is used rather than a coroutine job:
     * this must fire even if the calling scope is cancelled mid-clip, and it is
     * cheap to cancel by simply checking the player is still the active one.
     */
    private fun scheduleStop(
        player: MediaPlayer,
        limit: PlaybackLimit,
        finish: (String?) -> Unit
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                // Only act if this is still the player we started; otherwise
                // something else has already taken over and released it.
                if (isActive(player) && player.isPlaying) {
                    player.stop()
                    Diagnostics.log(appContext, "Playback stopped at ${limit.label} limit")
                }
            } catch (e: IllegalStateException) {
                // Already finished or released; nothing to stop.
            }
            finish(null)
        }, limit.millis)
    }

    private fun createPlayer(resolved: ResolvedSound, slot: SoundSlot): MediaPlayer? {
        // Custom sources first, falling back to the slot's bundled default when
        // the file has gone. A greeting the user can hear beats a correct error.
        if (resolved.available) {
            try {
                return when {
                    resolved.uri != null -> MediaPlayer().apply {
                        setAudioAttributes(attributes)
                        setDataSource(appContext, resolved.uri)
                        prepare()
                    }
                    resolved.file != null -> MediaPlayer().apply {
                        setAudioAttributes(attributes)
                        setDataSource(resolved.file.absolutePath)
                        prepare()
                    }
                    resolved.resId != null ->
                        MediaPlayer.create(appContext, resolved.resId, attributes, sessionId())
                    else -> null
                }
            } catch (e: Exception) {
                // Deleted between the check and here, unsupported codec, or a
                // revoked grant. Fall through to the bundled default.
                Diagnostics.log(appContext, "Sound failed to open (${e.message}); using built-in")
            }
        }

        val fallback = SoundCatalog.builtIn(slot.defaultBuiltIn) ?: return null
        return MediaPlayer.create(appContext, fallback.resId, attributes, sessionId())
    }

    /** Perceived-loudness curve; a linear slider feels broken. */
    private fun volumeToGain(percent: Int): Float {
        val clamped = percent.coerceIn(0, 100) / 100f
        return clamped.toDouble().pow(2.0).toFloat()
    }

    private fun sessionId(): Int =
        audioManager?.generateAudioSessionId() ?: AudioManager.AUDIO_SESSION_ID_GENERATE

    private fun requestFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    null, AudioManager.STREAM_MUSIC,
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

    /**
     * Plays an arbitrary file directly, without touching any slot's saved
     * settings. Used to audition a recording in the picker before assigning it.
     */
    suspend fun playFile(file: java.io.File, volumePercent: Int, limit: PlaybackLimit): String? {
        if (!file.exists() || file.length() == 0L) return "that recording is missing"

        stopActive()
        requestFocus()

        var player: MediaPlayer? = null
        try {
            val created = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                prepare()
            }
            player = created
            setActive(created)

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
                    Diagnostics.log(appContext, "Preview error what=$what extra=$extra")
                    finish("the audio system reported an error")
                    true
                }
                setActiveFinisher(created, finish = { finish("interrupted") })
                continuation.invokeOnCancellation { finish("cancelled") }
                try {
                    created.start()
                    if (limit.isLimited) scheduleStop(created, limit, ::finish)
                } catch (e: IllegalStateException) {
                    finish("the recording could not be played")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Preview failed: ${e.message}")
            return "could not play that recording"
        } finally {
            release(player)
            abandonFocus()
        }
    }

    /** Stops any playback in progress. Safe when nothing is playing. */
    fun stop() {
        stopActive()
        abandonFocus()
    }

    private fun release(player: MediaPlayer?) {
        if (player == null) return
        try {
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            player.setOnSeekCompleteListener(null)
            player.release()
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Player release threw: ${e.message}")
        }
        clearActive(player)
    }

    companion object {
        // Process-wide, because previews (ViewModel) and event sounds (service)
        // are different instances that must still never overlap.
        private val activeLock = Any()
        private var activePlayer: MediaPlayer? = null

        /**
         * Resumes the coroutine waiting on [activePlayer]. Held next to the
         * player so an interruption can end the wait: MediaPlayer.stop() fires
         * no completion callback, so without this a stopped clip would leave
         * its caller suspended indefinitely.
         */
        private var activeFinisher: (() -> Unit)? = null

        private fun setActive(player: MediaPlayer) {
            synchronized(activeLock) {
                activePlayer = player
                activeFinisher = null
            }
        }

        private fun setActiveFinisher(player: MediaPlayer, finish: () -> Unit) {
            synchronized(activeLock) {
                if (activePlayer === player) activeFinisher = finish
            }
        }

        private fun isActive(player: MediaPlayer): Boolean =
            synchronized(activeLock) { activePlayer === player }

        private fun clearActive(player: MediaPlayer) {
            synchronized(activeLock) {
                if (activePlayer === player) {
                    activePlayer = null
                    activeFinisher = null
                }
            }
        }

        private fun stopActive() {
            val finisher: (() -> Unit)?
            synchronized(activeLock) {
                val current = activePlayer
                finisher = activeFinisher
                if (current != null) {
                    try {
                        if (current.isPlaying) current.stop()
                    } catch (e: IllegalStateException) {
                        // Already finished or released; nothing to stop.
                    }
                }
                activePlayer = null
                activeFinisher = null
            }
            // Invoked outside the lock: the callback resumes a coroutine, and
            // resuming while holding a lock invites a deadlock.
            finisher?.invoke()
        }
    }
}
