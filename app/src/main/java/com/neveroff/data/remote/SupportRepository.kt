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
 * @support (§5).
 *
 * Every call here goes through a SECURITY DEFINER RPC that does not consult the
 * enforcement predicates. That is the point: §4 requires @support to remain
 * reachable while an account is suspended, when every other surface is locked.
 *
 * Support messages are deliberately NOT end-to-end encrypted — an agent has to
 * be able to read them — and the UI says so before the user writes anything.
 * They never pass through the `messages` table, so the ban gate on that table
 * needs no exception carved into it.
 */
object SupportRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    data class SubCategory(val key: String = "", val label: String = "")

    @Serializable
    data class Category(
        val key: String = "",
        val label: String = "",
        val description: String? = null,
        @SerialName("is_emergency") val isEmergency: Boolean = false,
        val children: List<SubCategory> = emptyList()
    )

    @Serializable
    data class TicketSummary(
        val id: String = "",
        @SerialName("ticket_ref") val ticketRef: String = "",
        val subject: String = "",
        @SerialName("category_key") val categoryKey: String = "",
        val status: String = "open",
        @SerialName("opened_at") val openedAt: String = "",
        @SerialName("last_agent_message_at") val lastAgentMessageAt: String? = null,
        @SerialName("awaiting_you") val awaitingYou: Boolean = false,
        @SerialName("satisfaction_score") val satisfactionScore: Int? = null
    ) {
        val isClosed: Boolean get() = status == "resolved" || status == "closed"
    }

    @Serializable
    data class ThreadMessage(
        @SerialName("author_type") val authorType: String = "user",
        @SerialName("author_label") val authorLabel: String = "",
        val body: String = "",
        @SerialName("sent_at") val sentAt: String = ""
    ) {
        val isMine: Boolean get() = authorType == "user"
    }

    @Serializable
    data class TicketReceipt(
        val id: String = "",
        @SerialName("ticket_ref") val ticketRef: String = "",
        @SerialName("expected_response_hours") val expectedResponseHours: Int = 24,
        @SerialName("is_emergency") val isEmergency: Boolean = false
    )

    /** Category buttons for the opening screen. Empty list on failure. */
    suspend fun categories(): List<Category> = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClient.client.postgrest.rpc(
                "support_categories",
                buildJsonObject { put("p_locale", Locale.getDefault().language) }
            )
            json.decodeFromString<List<Category>>(result.data)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Open a ticket. Throws on failure — a user who believes they have reached
     * support when they have not is the worst outcome this screen can produce,
     * and it is exactly the situation someone appealing a ban is in.
     */
    suspend fun openTicket(
        categoryKey: String,
        subject: String,
        body: String
    ): TicketReceipt = withContext(Dispatchers.IO) {
        val result = SupabaseClient.client.postgrest.rpc(
            "open_support_ticket",
            buildJsonObject {
                put("p_category_key", categoryKey)
                put("p_subject", subject)
                put("p_body", body)
                put("p_locale", Locale.getDefault().language)
            }
        )
        json.decodeFromString<TicketReceipt>(result.data)
    }

    suspend fun myTickets(): List<TicketSummary> = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClient.client.postgrest.rpc("my_support_tickets")
            json.decodeFromString<List<TicketSummary>>(result.data)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun thread(ticketId: String): List<ThreadMessage> = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClient.client.postgrest.rpc(
                "support_thread",
                buildJsonObject { put("p_ticket", ticketId) }
            )
            json.decodeFromString<List<ThreadMessage>>(result.data)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun reply(ticketId: String, body: String): Unit = withContext(Dispatchers.IO) {
        SupabaseClient.client.postgrest.rpc(
            "reply_to_support_ticket",
            buildJsonObject {
                put("p_ticket", ticketId)
                put("p_body", body)
            }
        )
    }

    suspend fun rate(ticketId: String, score: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            SupabaseClient.client.postgrest.rpc(
                "rate_support_ticket",
                buildJsonObject {
                    put("p_ticket", ticketId)
                    put("p_score", score)
                }
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
