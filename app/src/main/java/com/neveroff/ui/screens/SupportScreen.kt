package com.neveroff.ui.screens

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neveroff.data.remote.SupportRepository
import kotlinx.coroutines.launch

/**
 * @support (§5).
 *
 * This screen is reachable while an account is suspended — §4 makes it the one
 * surface a banned user can still open. It therefore has to work standing
 * alone: no chat list behind it, no profile, no navigation into the rest of the
 * app. Everything it needs comes from RPCs that ignore the ban.
 *
 * The self-harm path deliberately does not try to be clever. If someone picks
 * the safety category, they get crisis resources immediately, before the form —
 * a person in crisis should not have to fill in a subject line to be given a
 * phone number, and §5.2 is explicit that this path is never handled by
 * automation alone.
 */

private enum class Stage { LIST, PICK_CATEGORY, PICK_SUB, COMPOSE, THREAD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: (() -> Unit)? = null,
    /** Prefills a ban appeal when opened from the §4 blocking screen. */
    initialCategoryKey: String? = null,
    initialSubject: String? = null
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var stage by remember { mutableStateOf(if (initialCategoryKey != null) Stage.COMPOSE else Stage.LIST) }
    var categories by remember { mutableStateOf<List<SupportRepository.Category>>(emptyList()) }
    var tickets by remember { mutableStateOf<List<SupportRepository.TicketSummary>>(emptyList()) }

    var chosenCategory by remember { mutableStateOf<SupportRepository.Category?>(null) }
    var chosenKey by remember { mutableStateOf(initialCategoryKey ?: "") }
    var subject by remember { mutableStateOf(initialSubject ?: "") }
    var body by remember { mutableStateOf("") }

    var openTicket by remember { mutableStateOf<SupportRepository.TicketSummary?>(null) }
    var thread by remember { mutableStateOf<List<SupportRepository.ThreadMessage>>(emptyList()) }
    var reply by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastReceipt by remember { mutableStateOf<SupportRepository.TicketReceipt?>(null) }

    suspend fun refresh() {
        tickets = SupportRepository.myTickets()
        if (categories.isEmpty()) categories = SupportRepository.categories()
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    val isEmergencySelected = chosenCategory?.isEmergency == true ||
        chosenKey.startsWith("safety_emergency")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (stage) {
                            Stage.THREAD -> openTicket?.ticketRef ?: "Support"
                            Stage.COMPOSE -> "Tell us what is wrong"
                            else -> "Support"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    val canGoBack = stage != Stage.LIST || onBack != null
                    if (canGoBack) {
                        IconButton(onClick = {
                            error = null
                            when (stage) {
                                Stage.LIST -> onBack?.invoke()
                                Stage.PICK_CATEGORY -> stage = Stage.LIST
                                Stage.PICK_SUB -> stage = Stage.PICK_CATEGORY
                                Stage.COMPOSE -> stage =
                                    if (initialCategoryKey != null) Stage.LIST
                                    else if (chosenCategory?.children?.isNotEmpty() == true) Stage.PICK_SUB
                                    else Stage.PICK_CATEGORY
                                Stage.THREAD -> { openTicket = null; stage = Stage.LIST }
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                // ---------------- Ticket list ----------------
                stage == Stage.LIST -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Button(
                        onClick = { stage = Stage.PICK_CATEGORY },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(stringResource(R.string.start_a_new_conversation)) }

                    Spacer(Modifier.height(20.dp))

                    if (tickets.isEmpty()) {
                        Text(
                            stringResource(R.string.you_have_no_support_conversations_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    } else {
                        Text(
                            stringResource(R.string.your_conversations),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn {
                            items(tickets) { ticket ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            openTicket = ticket
                                            stage = Stage.THREAD
                                            scope.launch {
                                                thread = SupportRepository.thread(ticket.id)
                                            }
                                        }
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                ticket.subject,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (ticket.awaitingYou) {
                                                Badge { Text(stringResource(R.string.your_turn)) }
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "${ticket.ticketRef} · ${statusLabel(ticket.status)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.75f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ---------------- Category picker ----------------
                stage == Stage.PICK_CATEGORY -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                ) {
                    Text(
                        stringResource(R.string.what_do_you_need_help_with),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    categories.forEach { category ->
                        CategoryRow(category.label, category.description) {
                            chosenCategory = category
                            chosenKey = category.key
                            stage = if (category.children.isNotEmpty()) Stage.PICK_SUB
                                    else Stage.COMPOSE
                        }
                    }
                }

                // ---------------- Sub-category picker ----------------
                stage == Stage.PICK_SUB -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                ) {
                    Text(
                        chosenCategory?.label ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    chosenCategory?.children?.forEach { child ->
                        CategoryRow(child.label, null) {
                            chosenKey = child.key
                            stage = Stage.COMPOSE
                        }
                    }
                }

                // ---------------- Compose / receipt ----------------
                stage == Stage.COMPOSE -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                ) {
                    val receipt = lastReceipt
                    if (receipt != null) {
                        Text(
                            stringResource(R.string.we_have_got_it),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Your reference is ${receipt.ticketRef}. We aim to reply within " +
                                "${receipt.expectedResponseHours} hours, and you will see the " +
                                "answer here.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                lastReceipt = null
                                subject = ""; body = ""; chosenKey = ""; chosenCategory = null
                                stage = Stage.LIST
                                scope.launch { refresh() }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(stringResource(R.string.done)) }
                    } else {
                        // Crisis resources come before the form, not after it.
                        if (isEmergencySelected) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        stringResource(R.string.if_someone_is_in_immediate_danger),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        stringResource(R.string.please_contact_your_local_emergency_services) +
                                            "reach someone far faster than we can. We will still " +
                                            "read what you send here, and a person — not an " +
                                            "automated system — will look at it urgently.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    TextButton(onClick = {
                                        runCatching {
                                            uriHandler.openUri("https://neveroff.app/help/crisis")
                                        }
                                    }) { Text(stringResource(R.string.find_support_near_you)) }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }

                        OutlinedTextField(
                            value = subject,
                            onValueChange = { if (it.length <= 200) subject = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.summary)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = body,
                            onValueChange = { if (it.length <= 10000) body = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                            label = { Text(stringResource(R.string.what_happened)) },
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.unlike_your_chats_support_messages_are) +
                                "an agent has to be able to read them in order to help. Please " +
                                "do not include passwords or codes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )

                        error?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                if (subject.isBlank() || body.isBlank()) {
                                    error = "Please fill in both fields."
                                    return@Button
                                }
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        lastReceipt = SupportRepository.openTicket(
                                            chosenKey, subject.trim(), body.trim()
                                        )
                                    } catch (e: Exception) {
                                        error = e.message
                                            ?: "Could not send that. Please try again."
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            enabled = !busy
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else Text(stringResource(R.string.send))
                        }
                    }
                }

                // ---------------- Thread ----------------
                stage == Stage.THREAD -> Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(thread) { message ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    if (message.isMine) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (message.isMine)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.widthIn(max = 320.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        if (!message.isMine) {
                                            Text(
                                                message.authorLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.height(2.dp))
                                        }
                                        Text(message.body, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }

                    val ticket = openTicket
                    if (ticket != null && ticket.status == "closed") {
                        Text(
                            stringResource(R.string.this_conversation_is_closed_start_a),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = reply,
                                onValueChange = { if (it.length <= 10000) reply = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(R.string.reply)) },
                                shape = RoundedCornerShape(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val id = openTicket?.id ?: return@IconButton
                                    val text = reply.trim()
                                    if (text.isEmpty()) return@IconButton
                                    reply = ""
                                    scope.launch {
                                        runCatching { SupportRepository.reply(id, text) }
                                            .onFailure {
                                                error = it.message
                                                reply = text
                                            }
                                        thread = SupportRepository.thread(id)
                                        refresh()
                                    }
                                },
                                enabled = reply.isNotBlank()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                            }
                        }
                    }

                    // §5.1: satisfaction captured on resolution.
                    if (ticket != null && ticket.status == "resolved" &&
                        ticket.satisfactionScore == null
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.was_this_helpful), style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..5).forEach { score ->
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                SupportRepository.rate(ticket.id, score)
                                                refresh()
                                                openTicket = tickets.firstOrNull { it.id == ticket.id }
                                            }
                                        }) { Text("$score") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(label: String, description: String?, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "open" -> "Open"
    "pending_agent" -> "With support"
    "pending_user" -> "Waiting for you"
    "escalated" -> "Escalated"
    "resolved" -> "Resolved"
    "closed" -> "Closed"
    else -> status
}
