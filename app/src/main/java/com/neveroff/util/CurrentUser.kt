package com.neveroff.util

import android.content.Context
import com.neveroff.data.remote.SupabaseClient
import io.github.jan.supabase.gotrue.auth

/**
 * Single source of truth for "who am I".
 *
 * [SupabaseClient]'s auth session is not always resolvable — most notably with no network,
 * where `currentUserOrNull()` returns null until the session is restored/refreshed. Call
 * sites that fall back to "" in that case silently break identity comparisons: a message
 * whose `senderId` is compared against "" is never "mine", so an offline chat renders every
 * message as if it came from the other person (and anything constructed with that blank id,
 * e.g. ChatRepository, would persist outgoing messages with an empty senderId).
 *
 * The cached id is written on login/profile setup under "profile_id", with the older
 * "my_user_id" key kept as a fallback for installs that predate it.
 */
object CurrentUser {
    fun id(context: Context): String = id(
        context.getSharedPreferences("neveroff_prefs", Context.MODE_PRIVATE)
    )

    fun id(prefs: android.content.SharedPreferences): String =
        SupabaseClient.client.auth.currentUserOrNull()?.id
            ?: SecurePrefs.getString(prefs, "profile_id")
            ?: SecurePrefs.getString(prefs, "my_user_id")
            ?: ""
}
