package com.neveroff.util

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.neveroff.R

/**
 * The single place a [Throwable] is allowed to become text a user reads.
 *
 * Transport-layer exception messages are not safe to render. Ktor builds its
 * message from the whole request — `Client request(POST https://<project>.supabase.co
 * /rest/v1/channels?id=eq.…) invalid: 400` — and a failed request carries the
 * `Authorization: Bearer <JWT>` header along with it. Handing `e.message` to a
 * toast puts the caller's session token on screen, where it can be shoulder-surfed
 * or screenshotted into a bug report.
 *
 * So the raw message is used for *classification only* and never returned. Call
 * [toUserMessage] to get a localized sentence for the UI; the technical detail
 * goes to the log, redacted, via [ErrorReporter.report].
 */
object ErrorReporter {

    /**
     * Strips credentials out of text that is about to be written to a log file.
     *
     * Logs are exportable (see [LoggerManager.saveLogsToDisk]) and get attached to
     * support requests, so a bearer token in one leaves the device with it. Host
     * and path survive because they are what makes a log worth reading; the query
     * string does not, because PostgREST puts filter values there.
     */
    fun redact(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var out = raw
        // JWTs, wherever they appear — header value, query param, or bare in a body.
        out = JWT_PATTERN.replace(out, "<token>")
        // `Authorization: Bearer abc`, `apikey: abc`, `apikey=abc`.
        out = CREDENTIAL_HEADER_PATTERN.replace(out) { m -> "${m.groupValues[1]}<redacted>" }
        // Query strings can hold filter values and, on storage URLs, signed tokens.
        out = QUERY_STRING_PATTERN.replace(out, "?<params>")
        return out
    }

    /** Records [error] against [tag] with credentials stripped. Never surfaces to the UI. */
    fun report(tag: String, error: Throwable) {
        val safe = redact(error.message)
        Log.e(tag, "${error.javaClass.simpleName}: $safe", error)
        DebugLogger.log("$tag: ${error.javaClass.simpleName}: $safe")
    }

    /**
     * Picks the sentence that explains [error] to a person.
     *
     * Matching is done on the raw message because that is the only place the cause
     * is stated, but nothing from it reaches the return value — the result is always
     * one of our own string resources.
     */
    @StringRes
    fun userMessageRes(error: Throwable, @StringRes fallback: Int): Int {
        val raw = error.message.orEmpty()
        return when {
            error is java.net.UnknownHostException ||
                error is java.net.ConnectException ||
                raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("failed to connect", ignoreCase = true) -> R.string.error_no_connection

            error is java.net.SocketTimeoutException ||
                raw.contains("timeout", ignoreCase = true) -> R.string.error_timed_out

            // Supabase phrases its own throttle as "for security purposes".
            raw.contains("rate limit", ignoreCase = true) ||
                raw.contains("security purposes", ignoreCase = true) ||
                raw.contains("429") -> R.string.error_too_many_attempts

            raw.contains("JWT expired", ignoreCase = true) ||
                raw.contains("invalid_token", ignoreCase = true) ||
                raw.contains("401") -> R.string.error_session_expired

            // 23505 unique_violation — the row someone is adding already exists.
            raw.contains("23505") || raw.contains("duplicate key", ignoreCase = true) ->
                R.string.error_already_taken

            // 23514 check_violation — the value broke a table constraint. The
            // constraint name would mean nothing to a user, so it stays in the log.
            raw.contains("23514") || raw.contains("violates check constraint", ignoreCase = true) ->
                R.string.error_invalid_values

            raw.contains("row-level security", ignoreCase = true) ||
                raw.contains("42501") || raw.contains("403") -> R.string.error_not_allowed

            raw.contains("500") || raw.contains("502") || raw.contains("503") ->
                R.string.error_server_trouble

            else -> fallback
        }
    }

    private val JWT_PATTERN =
        Regex("""eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*""")

    private val CREDENTIAL_HEADER_PATTERN =
        Regex("""((?:Authorization|apikey|api_key|access_token|refresh_token)\s*[:=]\s*(?:Bearer\s+)?)\S+""",
            RegexOption.IGNORE_CASE)

    private val QUERY_STRING_PATTERN = Regex("""\?[^\s"')]+""")
}

/**
 * Logs [this] and returns a sentence safe to show, defaulting to [fallback].
 *
 * The one-line replacement for `AppToast.error(e.message ?: "…")`.
 */
fun Throwable.toUserMessage(
    context: Context,
    @StringRes fallback: Int = R.string.error_generic,
    tag: String = "App"
): String {
    ErrorReporter.report(tag, this)
    return context.getString(ErrorReporter.userMessageRes(this, fallback))
}
