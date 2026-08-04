package com.neveroff.data.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The current account's enforcement state, readable from anywhere.
 *
 * [EnforcementRepository] fetches it; MainActivity refreshes it; this holds it
 * so that any screen can ask "may I do this?" without the answer being drilled
 * through every composable in between. Restrictions apply across chat, channels,
 * search, profile and settings — threading a parameter through all of those
 * would guarantee that the one screen somebody forgot stays quietly usable.
 *
 * This is a convenience, not a security boundary. The server rejects restricted
 * writes through RLS whatever this object believes. What it buys is a user who
 * can see *why* a button is dead instead of tapping it and getting an opaque
 * failure — which §4 requires, and which is the whole difference between an
 * enforcement action and a bug.
 */
object EnforcementState {

    /**
     * Feature keys, matching admin.feature_catalogue in the console exactly.
     *
     * These strings are a contract with the console: an admin ticking "Send a
     * voice note" writes `send_voice_note` into restricted_features, and this is
     * the only place that spelling appears in the app. Anything that does not
     * match simply never restricts anything, silently — so they are collected
     * here rather than typed at each call site.
     */
    object Feature {
        const val SEND_MESSAGE = "send_message"
        const val SEND_VOICE_NOTE = "send_voice_note"
        const val SEND_IMAGE = "send_image"
        const val SEND_VIDEO = "send_video"
        const val CREATE_GROUP = "create_group"
        const val CREATE_CHANNEL = "create_channel"
        const val SUBSCRIBE_CHANNEL = "subscribe_channel"
        const val REACT_TO_POST = "react_to_post"
        const val SEARCH_USERS = "search_users"
        const val EDIT_PROFILE = "edit_profile"
        const val CHANGE_WALLPAPER = "change_wallpaper"
        const val SCHEDULE_MESSAGE = "schedule_message"

        /** Everything the console can restrict, for rendering the list to the user. */
        val ALL = listOf(
            SEND_MESSAGE, SEND_VOICE_NOTE, SEND_IMAGE, SEND_VIDEO,
            CREATE_GROUP, CREATE_CHANNEL, SUBSCRIBE_CHANNEL, REACT_TO_POST,
            SEARCH_USERS, EDIT_PROFILE, CHANGE_WALLPAPER, SCHEDULE_MESSAGE
        )
    }

    private val _status = MutableStateFlow(EnforcementRepository.UNRESTRICTED)
    val status: StateFlow<EnforcementRepository.Status> = _status.asStateFlow()

    /**
     * Replace the held state.
     *
     * A null fetch means "we could not reach the server", not "nothing applies",
     * so the previous state is kept rather than being cleared to unrestricted.
     * Dropping to unrestricted on a network blip would re-enable every button a
     * restricted user has, which is the one direction this must never fail in.
     * Clearing on sign-out is [reset]'s job, where it is unambiguous.
     */
    fun update(status: EnforcementRepository.Status?) {
        if (status != null) _status.value = status
    }

    /** Sign-out: this account's restrictions must not follow the next one in. */
    fun reset() {
        _status.value = EnforcementRepository.UNRESTRICTED
    }

    /**
     * Whether a specific feature is currently blocked.
     *
     * `send_message` is treated as restricted whenever sending is blocked at all,
     * so a mute — which arrives as blocks_sending rather than as a feature list —
     * greys out the composer through the same path as an explicit feature
     * restriction. One rule for the UI to follow beats two that must agree.
     */
    fun isRestricted(feature: String): Boolean {
        val s = _status.value
        if (s.blocksAccess) return true
        if (s.restrictedFeatures.contains(feature)) return true
        return s.blocksSending && feature in SENDING_FEATURES
    }

    /** Features that are forms of sending, and so fall under a mute. */
    private val SENDING_FEATURES = setOf(
        Feature.SEND_MESSAGE, Feature.SEND_VOICE_NOTE, Feature.SEND_IMAGE,
        Feature.SEND_VIDEO, Feature.SCHEDULE_MESSAGE, Feature.REACT_TO_POST
    )

    /**
     * Everything currently in force, for display.
     *
     * Shadow restriction is deliberately absent from this list and from every
     * other user-facing surface. Telling someone their reach has been quietly
     * limited would defeat the entire mechanism — it exists precisely so that a
     * bad actor keeps behaving normally instead of evading. It is applied
     * server-side and the client neither shows nor reacts to it.
     */
    fun activeRestrictions(): List<String> {
        val s = _status.value
        val explicit = s.restrictedFeatures.filter { it in Feature.ALL }
        return if (s.blocksSending) {
            (explicit + SENDING_FEATURES).distinct()
        } else {
            explicit
        }
    }

    /** True when there is anything to tell the user about. */
    fun hasVisibleRestrictions(): Boolean =
        _status.value.blocksSending || activeRestrictions().isNotEmpty()
}

/** Observe enforcement state from a composable. */
@Composable
fun rememberEnforcement(): State<EnforcementRepository.Status> =
    EnforcementState.status.collectAsState()
