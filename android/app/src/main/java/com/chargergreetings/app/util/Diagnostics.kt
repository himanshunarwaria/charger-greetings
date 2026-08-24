package com.chargergreetings.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small, size-capped local log.
 *
 * The whole point of this app is that it does its job while nobody is watching,
 * which makes "it didn't play and I don't know why" the failure mode that
 * matters. The settings screen can show the last few lines of this file, so a
 * user can see *why* a greeting was skipped — cooldown, silent mode, switched
 * off — without any developer tooling.
 *
 * It lives in the app's private storage, holds only power-state transitions and
 * decisions, and is deleted when the app is uninstalled. Nothing is transmitted.
 */
object Diagnostics {

    private const val TAG = "ChargerGreetings"
    private const val FILE_NAME = "diagnostics.log"
    private const val MAX_BYTES = 32 * 1024
    private const val KEEP_LINES = 120

    private val lock = Any()

    fun log(context: Context, message: String) {
        Log.d(TAG, message)
        synchronized(lock) {
            try {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val kept = file.readLines().takeLast(KEEP_LINES / 2)
                    file.writeText(kept.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
                }
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText("$stamp  $message" + System.lineSeparator())
            } catch (e: Exception) {
                // Logging must never be the reason something fails.
                Log.w(TAG, "Could not write diagnostics: ${e.message}")
            }
        }
    }

    /** Most recent entries, newest last. Used by the settings screen. */
    fun recent(context: Context, limit: Int = 20): List<String> = synchronized(lock) {
        try {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (!file.exists()) emptyList() else file.readLines().takeLast(limit)
        } catch (e: Exception) {
            listOf("Could not read the log: ${e.message}")
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
        Unit
    }
}
