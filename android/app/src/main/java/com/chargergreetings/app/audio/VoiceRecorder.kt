package com.chargergreetings.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.chargergreetings.app.util.Diagnostics
import java.io.File

/**
 * Records a short voice memo into app-private storage.
 *
 * ### Privacy posture
 * The microphone is only ever opened from an explicit user action on screen.
 * Nothing here is reachable from the service, the receivers or the watchdog, so
 * the app cannot record in the background even by accident. Recordings are
 * written to `filesDir/recordings`, which is app-private: no other app can read
 * them, they never touch shared storage, and they are removed when the app is
 * uninstalled.
 *
 * ### Format
 * AAC in an MP4 container (`.m4a`): small, and decodable by MediaPlayer on
 * every supported API level without a codec question.
 *
 * Not thread-safe by design; it is driven from the UI thread only.
 */
class VoiceRecorder(context: Context) {

    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var paused = false

    /** True while a recording is in progress (including paused). */
    val isRecording: Boolean get() = recorder != null

    val isPaused: Boolean get() = paused

    /** Pause/resume needs API 24+, which is this app's minSdk, so always true. */
    val supportsPause: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    /**
     * Starts recording to a new file.
     * @return null on success, or a user-presentable failure reason.
     */
    fun start(): String? {
        if (recorder != null) return "Already recording"

        val file = File(SoundCatalog.recordingsDir(appContext), "voice_${System.currentTimeMillis()}.m4a")
        return try {
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96_000)
                // A hard ceiling so a forgotten recording cannot fill storage.
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = created
            target = file
            paused = false
            Diagnostics.log(appContext, "Recording started")
            null
        } catch (e: SecurityException) {
            cleanupAfterFailure(file)
            "Microphone permission is required to record."
        } catch (e: Exception) {
            cleanupAfterFailure(file)
            Diagnostics.log(appContext, "Recorder start failed: ${e.message}")
            "Could not start recording: ${e.message ?: "unknown error"}"
        }
    }

    fun pause(): String? {
        val active = recorder ?: return "Not recording"
        if (!supportsPause) return "Pause is not supported on this Android version"
        return try {
            active.pause()
            paused = true
            null
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Recorder pause failed: ${e.message}")
            "Could not pause"
        }
    }

    fun resume(): String? {
        val active = recorder ?: return "Not recording"
        if (!supportsPause) return "Resume is not supported on this Android version"
        return try {
            active.resume()
            paused = false
            null
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Recorder resume failed: ${e.message}")
            "Could not resume"
        }
    }

    /**
     * Stops and keeps the file.
     * @return the saved file, or null if the recording could not be used.
     */
    fun stopAndSave(): File? {
        val active = recorder ?: return null
        val file = target
        return try {
            active.stop()
            releaseRecorder()
            // A stop within a fraction of a second of start produces a
            // zero-length or header-only file that will not play. Reject it
            // here rather than let the user save silence.
            if (file != null && file.exists() && file.length() > MIN_USABLE_BYTES) {
                Diagnostics.log(appContext, "Recording saved (${file.length()} bytes)")
                file
            } else {
                file?.delete()
                Diagnostics.log(appContext, "Recording too short, discarded")
                null
            }
        } catch (e: RuntimeException) {
            // MediaRecorder.stop() throws if stop happens before any frames
            // were written. The partial file is unusable.
            releaseRecorder()
            file?.delete()
            Diagnostics.log(appContext, "Recording stop failed (too short): ${e.message}")
            null
        }
    }

    /** Stops and discards the file. */
    fun cancel() {
        val active = recorder
        val file = target
        if (active != null) {
            try {
                active.stop()
            } catch (e: RuntimeException) {
                // Expected when cancelling immediately after start.
            }
        }
        releaseRecorder()
        try {
            file?.delete()
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Could not delete cancelled recording: ${e.message}")
        }
        Diagnostics.log(appContext, "Recording cancelled")
    }

    private fun releaseRecorder() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Recorder release threw: ${e.message}")
        }
        recorder = null
        target = null
        paused = false
    }

    private fun cleanupAfterFailure(file: File) {
        releaseRecorder()
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Diagnostics.log(appContext, "Could not clean up failed recording: ${e.message}")
        }
    }

    private companion object {
        const val MAX_DURATION_MS = 30_000
        const val MIN_USABLE_BYTES = 2_000L
    }
}
