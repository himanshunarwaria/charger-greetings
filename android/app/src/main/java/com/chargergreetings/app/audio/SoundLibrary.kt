package com.chargergreetings.app.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.chargergreetings.app.util.Diagnostics


/**
 * Storage Access Framework helpers: taking, releasing and validating the
 * long-term access that makes a user-picked file survive a reboot.
 *
 * ### Why persistable URI permissions matter here
 * A plain SAF pick grants read access only until the process dies. This app's
 * whole purpose is to work months later, after reboots, with the app closed --
 * so [takePersistableAccess] must be called at pick time or the sound silently
 * stops working on the next boot. That is a classic cause of "it worked
 * yesterday" bugs in exactly this kind of app.
 */
object SoundLibrary {

    /** MIME types offered in the picker. */
    val PICKER_MIME_TYPES = arrayOf("audio/*")

    /**
     * Takes long-term read permission on a freshly picked document.
     * @return null on success, or a reason the pick could not be kept.
     */
    fun takePersistableAccess(context: Context, uri: Uri): String? = try {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        null
    } catch (e: SecurityException) {
        // Some providers (and some OEM file pickers) hand back a one-shot grant
        // that cannot be persisted. The sound will work now but not after a
        // reboot, so tell the user rather than let it fail quietly later.
        Diagnostics.log(context, "Could not persist access to " + uri + ": " + e.message)
        "Android would not grant lasting access to that file. " +
            "Try picking it from Files or Downloads instead of a cloud app."
    }

    /** Releases a persisted grant we no longer need, so we are not holding it forever. */
    fun releaseAccess(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Already gone; nothing to release.
            Diagnostics.log(context, "Nothing to release for " + uri + ": " + e.message)
        }
    }

    /**
     * Cheapest reliable availability check: actually open the stream. Checking
     * only the permission list would still pass for a file that has since been
     * deleted, which is the common real-world case.
     */
    fun canOpen(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) return value
                }
            }
        } catch (e: Exception) {
            Diagnostics.log(context, "Could not read display name: " + e.message)
        }
        return uri.lastPathSegment ?: "Selected sound"
    }

    const val BUILT_IN_LABEL = "Built-in greeting"
}
