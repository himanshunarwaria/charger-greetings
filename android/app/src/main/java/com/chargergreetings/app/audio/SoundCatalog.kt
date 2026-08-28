package com.chargergreetings.app.audio

import android.content.Context
import android.net.Uri
import com.chargergreetings.app.R
import com.chargergreetings.app.core.BuiltInSoundIds
import com.chargergreetings.app.core.SoundSlot
import com.chargergreetings.app.core.SoundSource
import com.chargergreetings.app.util.Diagnostics
import java.io.File

/** One entry in the bundled collection. */
data class BuiltInSound(val id: String, val label: String, val resId: Int)

/**
 * Resolves any [SoundSource] to something playable, and reports honestly when
 * it cannot.
 *
 * This is the single place that knows how the three source kinds differ, so the
 * player, the UI and the event handler can all treat a sound as one thing.
 */
object SoundCatalog {

    /**
     * The bundled collection.
     *
     * Every tone here is synthesised from scratch by `tools/ToneGen` (additive
     * synthesis, exponential decay), so the audio is original and carries no
     * licensing obligation. The two spoken greetings are the user's own
     * recordings. Nothing is sampled from a third party.
     */
    val builtIns: List<BuiltInSound> = listOf(
        BuiltInSound(BuiltInSoundIds.GREETING_CONNECTED, "Greeting: मालिक, प्रणाम", R.raw.power_connected),
        BuiltInSound(BuiltInSoundIds.GREETING_DISCONNECTED, "Greeting: फिर मिलते हैं, मालिक", R.raw.power_disconnected),
        BuiltInSound(BuiltInSoundIds.CHIME_UP, "Chime up", R.raw.chime_up),
        BuiltInSound(BuiltInSoundIds.CHIME_DOWN, "Chime down", R.raw.chime_down),
        BuiltInSound(BuiltInSoundIds.DING, "Ding", R.raw.ding),
        BuiltInSound(BuiltInSoundIds.MARIMBA, "Marimba", R.raw.marimba),
        BuiltInSound(BuiltInSoundIds.SOFT_BEEP, "Soft beep", R.raw.soft_beep),
        BuiltInSound(BuiltInSoundIds.PEBBLE, "Pebble", R.raw.pebble),
        BuiltInSound(BuiltInSoundIds.ALERT_PING, "Alert ping", R.raw.alert_ping)
    )

    fun builtIn(id: String): BuiltInSound? = builtIns.firstOrNull { it.id == id }

    /** Directory holding voice recordings, inside app-private storage. */
    fun recordingsDir(context: Context): File =
        File(context.filesDir, "recordings").apply { mkdirs() }

    fun recordingFile(context: Context, fileName: String) =
        File(recordingsDir(context), fileName)

    fun savedRecordings(context: Context): List<File> =
        recordingsDir(context).listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * What a slot will actually play, including whether it is usable.
     * Falls back to the slot's bundled default when a custom source has gone.
     */
    fun resolve(context: Context, slot: SoundSlot, source: SoundSource): ResolvedSound =
        when (source) {
            is SoundSource.BuiltIn -> {
                val entry = builtIn(source.id)
                if (entry != null) {
                    ResolvedSound(source, entry.label, resId = entry.resId, available = true)
                } else {
                    // A built-in id that no longer exists (downgrade, or a
                    // renamed asset). Fall back rather than fail.
                    val fallback = builtIn(slot.defaultBuiltIn)
                    ResolvedSound(
                        source, "Unknown sound", resId = fallback?.resId,
                        available = false,
                        problem = "That built-in sound is no longer available. Pick another."
                    )
                }
            }

            is SoundSource.FileUri -> {
                val uri = runCatching { Uri.parse(source.uri) }.getOrNull()
                val label = uri?.let { SoundLibrary.displayName(context, it) } ?: "Selected file"
                if (uri != null && SoundLibrary.canOpen(context, uri)) {
                    ResolvedSound(source, label, uri = uri, available = true)
                } else {
                    ResolvedSound(
                        source, label, uri = uri, available = false,
                        problem = "This file can no longer be opened. It may have been " +
                            "moved or deleted. Choose it again, or reset to a built-in sound."
                    )
                }
            }

            is SoundSource.Recording -> {
                val file = recordingFile(context, source.fileName)
                val label = "Your recording"
                if (file.exists() && file.length() > 0) {
                    ResolvedSound(source, label, file = file, available = true)
                } else {
                    ResolvedSound(
                        source, label, available = false,
                        problem = "That recording is missing. Record again, or reset to a " +
                            "built-in sound."
                    )
                }
            }
        }

    /** Deletes a recording and reports whether it worked. */
    fun deleteRecording(context: Context, fileName: String): Boolean = try {
        recordingFile(context, fileName).delete()
    } catch (e: Exception) {
        Diagnostics.log(context, "Could not delete recording: " + e.message)
        false
    }
}

/**
 * A source resolved against the device right now. Exactly one of
 * [resId], [uri] or [file] is set when [available] is true.
 */
data class ResolvedSound(
    val source: SoundSource,
    val label: String,
    val resId: Int? = null,
    val uri: Uri? = null,
    val file: File? = null,
    val available: Boolean,
    val problem: String? = null
) {
    val isCustom: Boolean get() = source !is SoundSource.BuiltIn
}
