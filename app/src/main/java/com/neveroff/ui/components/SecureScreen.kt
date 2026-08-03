package com.neveroff.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Blocks screenshots and screen recording while [active] is true, and only then.
 *
 * FLAG_SECURE is a window-level flag, so it is easy to turn on somewhere and
 * forget about — leaving the whole app unscreenshotable, which is user-hostile
 * for something as ordinary as sharing a chat with a friend. This composable
 * exists so the flag is scoped to a piece of UI rather than to the process:
 *
 *   - it clears the flag whenever [active] goes false, and
 *   - it clears the flag on dispose, so navigating away or backgrounding always
 *     restores normal behaviour even if the caller forgets.
 *
 * The one place it is switched on is a chat holding a temporary message from
 * the other person: those are meant to disappear, and a screenshot defeats
 * that. Everywhere else screenshots stay available.
 *
 * Worth being honest about what this does not do: FLAG_SECURE stops the system
 * screenshot and most screen recorders. It does not stop someone photographing
 * the screen with another phone. It raises the effort, it does not make the
 * message unrecoverable, and the UI should not imply otherwise.
 */
@Composable
fun SecureScreen(active: Boolean) {
    val context = LocalContext.current

    DisposableEffect(active) {
        val window = (context as? Activity)?.window

        if (active) {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        onDispose {
            // Unconditional: leaving this screen must never leave the rest of
            // the app locked down.
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
