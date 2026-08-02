package com.neveroff.data.remote

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Reads this account's enforcement state from the admin console's backend.
 *
 * Everything here goes through SECURITY DEFINER RPCs keyed on auth.uid(), so a
 * caller can only ever see or act on their own record — there is no parameter
 * through which one user could ask about another.
 *
 * The reason text is localised server-side from the console's reason catalogue
 * and returned ready to display. That is deliberate: keeping the catalogue in
 * one place means a reason code added after this app version shipped still
 * renders as readable text rather than as a raw code.
 */
object EnforcementRepository {

    /**
     * Own Json instance rather than the Supabase client's default. The server
     * payload will grow over time, and a strict decoder would start throwing on
     * a field this app version has never heard of — on the one screen a blocked
     * user can actually reach.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    data class Reason(
        val code: String = "",
        val category: String = "",
        @SerialName("policy_clause") val policyClause: String = "",
        val appealable: Boolean = true,
        /** Already localised by the server for the locale we asked for. */
        val label: String = ""
    )

    @Serializable
    data class Appeal(
        val state: String = "none",
        val id: String? = null,
        @SerialName("submitted_at") val submittedAt: String? = null,
        @SerialName("reviewed_at") val reviewedAt: String? = null,
        @SerialName("sla_due_at") val slaDueAt: String? = null
    )

    @Serializable
    data class Status(
        val status: String = "active",
        @SerialName("blocks_access") val blocksAccess: Boolean = false,
        @SerialName("blocks_sending") val blocksSending: Boolean = false,
        @SerialName("case_ref") val caseRef: String? = null,
        @SerialName("action_id") val actionId: String? = null,
        @SerialName("issued_at") val issuedAt: String? = null,
        @SerialName("expires_at") val expiresAt: String? = null,
        @SerialName("acknowledged_at") val acknowledgedAt: String? = null,
        @SerialName("restricted_features") val restrictedFeatures: List<String> = emptyList(),
        @SerialName("rate_limit_per_minute") val rateLimitPerMinute: Int? = null,
        val reason: Reason? = null,
        val appeal: Appeal? = null
    ) {
        val isPermanent: Boolean get() = status == "banned_permanent" || status == "deleted"
        val canAppeal: Boolean
            get() = (reason?.appealable ?: false) &&
                (appeal?.state == null || appeal.state == "none")
    }

    /** The unrestricted default, used whenever we cannot reach the server. */
    val UNRESTRICTED = Status()

    /**
     * Fetch the current state.
     *
     * Returns null on any failure rather than throwing. A network blip must not
     * lock a user out of their own app — the enforcement that matters is applied
     * server-side by RLS regardless of what this client believes, so failing
     * open here costs nothing and failing closed would be a self-inflicted outage.
     */
    suspend fun fetchStatus(): Status? = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClient.client.postgrest.rpc(
                "my_enforcement_status",
                buildJsonObject { put("p_locale", Locale.getDefault().language) }
            )
            json.decodeFromString<Status>(result.data)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Serializable
    data class AppealReceipt(
        val id: String = "",
        @SerialName("case_ref") val caseRef: String = "",
        val status: String = "pending",
        @SerialName("sla_hours") val slaHours: Int = 72
    )

    /**
     * Submit an appeal against a specific action.
     *
     * Throws on failure so the form can show a real error — unlike the status
     * read, a silently swallowed failure here would leave someone believing they
     * had appealed when they had not.
     */
    suspend fun submitAppeal(actionId: String, body: String): AppealReceipt =
        withContext(Dispatchers.IO) {
            val result = SupabaseClient.client.postgrest.rpc(
                "submit_appeal",
                buildJsonObject {
                    put("p_action_id", actionId)
                    put("p_body", body)
                    put("p_locale", Locale.getDefault().language)
                }
            )
            json.decodeFromString<AppealReceipt>(result.data)
        }

    /** Acknowledge a warning so the notice stops being shown. */
    suspend fun acknowledgeWarning(actionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            SupabaseClient.client.postgrest.rpc(
                "acknowledge_warning",
                buildJsonObject { put("p_action_id", actionId) }
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
