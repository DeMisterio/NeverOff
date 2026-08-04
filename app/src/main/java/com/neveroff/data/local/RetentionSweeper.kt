package com.neveroff.data.local

import android.content.Context
import com.neveroff.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enforces the auto-delete setting.
 *
 * This is the half that was missing. The slider in Settings wrote a number to
 * SharedPreferences and nothing anywhere read it, so a user who set messages to
 * delete after seven days kept every message forever and had no way to tell.
 * A privacy control that silently does nothing is worse than not offering one,
 * because people arrange their behaviour around believing it works.
 *
 * Runs on resume rather than on a timer: the window is measured in hours at the
 * shortest, so waking the device to prune on schedule would cost battery to
 * gain minutes of precision nobody can perceive. What matters is that opening
 * the app never shows a message that should already be gone.
 */
object RetentionSweeper {

    /**
     * Deletes anything past its window.
     *
     * @return how many rows went, for logging. Zero is the normal case.
     */
    suspend fun sweep(context: Context): Int = withContext(Dispatchers.IO) {
        val minutes = SettingsManager.autoDeleteMinutesFlow.value
        if (minutes <= 0) return@withContext 0

        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        try {
            val db = AppDatabase.getDatabase(context)
            val removed = db.messageDao().deleteOlderThan(cutoff)
            if (removed > 0) {
                com.neveroff.util.DebugLogger.log(
                    "Retention: removed $removed message(s) older than $minutes minutes"
                )
            }
            removed
        } catch (e: Exception) {
            // A failed sweep must never stop the app opening. The messages stay
            // one cycle longer, which is a far smaller problem than a crash on
            // launch for everyone who turned this on.
            e.printStackTrace()
            0
        }
    }
}
