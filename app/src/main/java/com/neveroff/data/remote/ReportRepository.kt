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

/**
 * Reporting a message, user or channel (§6.1–6.3).
 *
 * Everything goes through the `submit_report` RPC, which is SECURITY DEFINER
 * and keyed on auth.uid(). The server clusters reports about the same target
 * into one case, so five people reporting the same channel produces one piece
 * of work rather than five.
 *
 * EVIDENCE AND ENCRYPTION. NeverOff messages are end-to-end encrypted, so the
 * server holds ciphertext it cannot read. If a moderator is to see what was
 * actually said, this device has to send it — it is the only party that can
 * decrypt it. That is why [submitReport] takes `evidenceText` and why the UI
 * asks for explicit consent before including it. Reporting without it is
 * perfectly valid: the report still counts towards the pattern, it just cannot
 * be judged on content.
 */
object ReportRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** What the report is about. Must match the server's target_type values. */
    enum class TargetType(val wire: String) {
        MESSAGE("message"),
        USER("user"),
        CHANNEL("channel"),
        CHANNEL_POST("channel_post")
    }

    /** What the reporter would like to happen, beyond the report itself. */
    enum class OutcomePreference(val wire: String, val label: String) {
        JUST_REPORT("just_report", "Just report it"),
        BLOCK_USER("block_user", "Report and block them"),
        LEAVE_CHANNEL("leave_channel", "Report and leave the channel"),
        HIDE_CONTENT("hide_content", "Report and hide this content")
    }

    data class Reason(val code: String, val label: String)
    data class Category(val key: String, val label: String, val reasons: List<Reason>)

    /**
     * The §6.2 two-level taxonomy, mirrored from the console's reason
     * catalogue so the picker works without a round trip.
     *
     * The server is the authority and validates every code, so a stale copy
     * here fails closed with "Unknown reason code" rather than silently
     * mis-filing a report. Worth replacing with a fetched catalogue once there
     * is somewhere sensible to cache it.
     */
    val CATEGORIES: List<Category> = listOf(
        Category("spam_scams", "Spam and scams", listOf(
            Reason("spam.unsolicited_bulk", "Unwanted bulk messages"),
            Reason("spam.phishing", "Phishing or trying to steal my login"),
            Reason("spam.fake_giveaway", "Fake giveaway or prize"),
            Reason("spam.investment_fraud", "Investment or crypto scam"),
            Reason("spam.platform_impersonation", "Pretending to be NeverOff staff")
        )),
        Category("harassment", "Harassment and bullying", listOf(
            Reason("harassment.targeted_abuse", "Targeted abuse"),
            Reason("harassment.threats", "Threatening someone"),
            Reason("harassment.stalking", "Stalking or unwanted contact"),
            Reason("harassment.coordinated_pile_on", "Coordinated pile-on"),
            Reason("harassment.doxxing", "Sharing private information")
        )),
        Category("hate_speech", "Hate speech", listOf(
            Reason("hate.protected_characteristic", "Attacking people for who they are"),
            Reason("hate.dehumanising_content", "Dehumanising content")
        )),
        Category("violence", "Violence", listOf(
            Reason("violence.threat", "Threatening violence"),
            Reason("violence.incitement", "Encouraging violence"),
            Reason("violence.glorification", "Glorifying violence"),
            Reason("violence.violent_extremism", "Violent extremist content")
        )),
        Category("adult_sexual", "Adult and sexual content", listOf(
            Reason("adult.ncii", "Intimate images shared without consent"),
            Reason("adult.unsolicited_sexual", "Unwanted sexual content"),
            Reason("adult.sexual_services", "Advertising sexual services")
        )),
        Category("child_safety", "Child safety", listOf(
            Reason("child_safety.csam", "Sexual content involving a child"),
            Reason("child_safety.grooming", "An adult targeting a child"),
            Reason("child_safety.endangerment", "A child is in danger")
        )),
        Category("self_harm", "Self-harm or suicide", listOf(
            Reason("self_harm.risk_disclosure", "I am worried about someone"),
            Reason("self_harm.promotion", "Encouraging self-harm")
        )),
        Category("illegal_goods", "Illegal goods", listOf(
            Reason("illegal.drugs", "Selling drugs"),
            Reason("illegal.weapons", "Selling weapons"),
            Reason("illegal.stolen_data", "Selling stolen data"),
            Reason("illegal.counterfeit", "Selling counterfeit goods")
        )),
        Category("impersonation", "Impersonation", listOf(
            Reason("impersonation.person", "Pretending to be someone else"),
            Reason("impersonation.brand", "Pretending to be a company"),
            Reason("impersonation.official_account", "Pretending to be an official account")
        )),
        Category("misinformation", "Misinformation", listOf(
            Reason("misinformation.civic", "Misleading civic information"),
            Reason("misinformation.health", "Misleading health information")
        )),
        Category("intellectual_property", "Intellectual property", listOf(
            Reason("ip.copyright", "Copyright infringement"),
            Reason("ip.trademark", "Trademark infringement")
        )),
        Category("other", "Something else", listOf(
            Reason("other.terms_violation", "Breaking the rules"),
            Reason("other.security_abuse", "Attacking the platform")
        ))
    )

    /** Categories that never route to the ordinary queue — see [Receipt.routing]. */
    fun isSensitive(categoryKey: String): Boolean =
        categoryKey == "child_safety" || categoryKey == "self_harm"

    @Serializable
    data class Receipt(
        val status: String = "received",
        /** "moderation_queue", "priority_review" or "safety_escalation". */
        val routing: String = "moderation_queue",
        val message: String = "Thank you. We will review this."
    )

    /**
     * File a report.
     *
     * @param evidenceText plaintext the reporter has chosen to share. For an
     *   encrypted message this is the only way a moderator can ever see the
     *   content, so passing null is a real choice with real consequences —
     *   the UI should say so rather than defaulting it on silently.
     * @param contextConsented whether surrounding messages may also be shared.
     *
     * Throws on failure so the dialog can show a real error. A report the user
     * believes was filed but was not is worse than a visible failure.
     */
    suspend fun submitReport(
        targetType: TargetType,
        targetId: String,
        reasonCode: String,
        note: String? = null,
        outcomePreference: OutcomePreference = OutcomePreference.JUST_REPORT,
        evidenceText: String? = null,
        contextConsented: Boolean = false
    ): Receipt = withContext(Dispatchers.IO) {
        val result = SupabaseClient.client.postgrest.rpc(
            "submit_report",
            buildJsonObject {
                put("p_target_type", targetType.wire)
                put("p_target_id", targetId)
                put("p_reason_code", reasonCode)
                put("p_note", note)
                put("p_outcome_preference", outcomePreference.wire)
                put("p_evidence_text", evidenceText)
                put("p_context_consented", contextConsented)
            }
        )
        json.decodeFromString<Receipt>(result.data)
    }

    @Serializable
    data class ReportHistoryEntry(
        @SerialName("submitted_at") val submittedAt: String = "",
        @SerialName("reason_code") val reasonCode: String = "",
        @SerialName("target_type") val targetType: String = "",
        /** received | under_review | reviewed_action_taken | reviewed_no_violation */
        val state: String = "received"
    )

    /**
     * The reporter's own history. Deliberately generic: it never reveals the
     * case, the reported party's status, or what action was taken (§6.4).
     */
    suspend fun myReports(): List<ReportHistoryEntry> = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClient.client.postgrest.rpc("my_reports")
            json.decodeFromString<List<ReportHistoryEntry>>(result.data)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
