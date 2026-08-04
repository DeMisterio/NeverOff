package com.neveroff.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neveroff.data.remote.EnforcementRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * The §4 warning notice.
 *
 * A warning does not block access, so unlike [BanScreen] this is not a route —
 * it is drawn over the whole app and taken away once acknowledged. It is opaque
 * and full-screen rather than a dialog for one reason: a warning is a formal
 * step towards suspension, and something a user can dismiss by tapping outside
 * it is something they can miss entirely and then be suspended without ever
 * having read.
 *
 * Acknowledgement is recorded server-side, which is what stops the notice
 * coming back on the next launch. If that call cannot be made the notice stays
 * — but see [onDefer]: refusing to let someone into their messenger because
 * their train went into a tunnel is not a proportionate response to a warning.
 */
@Composable
fun WarningNoticeScreen(
    status: EnforcementRepository.Status,
    /** Acknowledged on the server; the caller should re-check enforcement state. */
    onAcknowledged: () -> Unit,
    /** Hide until the next launch, for when the acknowledgement cannot be sent. */
    onDefer: () -> Unit,
    onOpenSupport: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var submitting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val issuedText = remember(status.issuedAt) { formatNoticeTimestamp(status.issuedAt) }
    val expiryText = remember(status.expiresAt) { formatNoticeTimestamp(status.expiresAt) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Opaque: this sits on top of the running app, and a translucent
            // surface here would leave the chat list legible underneath and
            // read as a dismissible toast rather than a formal notice.
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = if (status.blocksSending) "Your account has been restricted"
                       else "You've received a warning",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = buildString {
                    append(
                        if (status.blocksSending) {
                            "A moderator has limited what you can do on NeverOff."
                        } else {
                            "A moderator has reviewed your account. Nothing is blocked, " +
                                "but further breaches can lead to a suspension."
                        }
                    )
                    if (issuedText != null) append("\n\nIssued $issuedText.")
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
            )

            Spacer(Modifier.height(28.dp))

            // --- Reason ------------------------------------------------------
            status.reason?.let { reason ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = "Reason",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            // Localised server-side from the console's reason
                            // catalogue, so this is never a raw code.
                            text = reason.label.ifBlank { reason.code },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (reason.policyClause.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            TextButton(
                                onClick = {
                                    runCatching {
                                        uriHandler.openUri(
                                            "https://neveroff.app/policy#${reason.policyClause}"
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Read policy section ${reason.policyClause}")
                            }
                        }
                    }
                }
            }

            // --- What is restricted ------------------------------------------
            if (status.blocksSending || status.restrictedFeatures.isNotEmpty() ||
                status.rateLimitPerMinute != null
            ) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = "What this affects",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(8.dp))

                        if (status.blocksSending) {
                            NoticeBullet("You can't send messages or post while this is active.")
                        }
                        status.restrictedFeatures.forEach { feature ->
                            NoticeBullet(featureLabel(feature))
                        }
                        status.rateLimitPerMinute?.let { limit ->
                            NoticeBullet("You can send up to $limit messages per minute.")
                        }
                        if (expiryText != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "This lifts automatically on $expiryText.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // --- Case reference ------------------------------------------------
            status.caseRef?.let { caseRef ->
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = "Reference",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(6.dp))
                        // Selectable: people need to quote this in correspondence.
                        SelectionContainer {
                            Text(
                                text = caseRef,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            if (failed) {
                Text(
                    text = "Couldn't confirm this with the server. You can try again, " +
                        "or continue and we'll show it to you next time.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    val actionId = status.actionId
                    if (actionId.isNullOrBlank()) {
                        // Nothing to acknowledge against; don't trap the user
                        // behind a button that can never succeed.
                        onDefer()
                        return@Button
                    }
                    submitting = true
                    failed = false
                    scope.launch {
                        val ok = EnforcementRepository.acknowledgeWarning(actionId)
                        submitting = false
                        if (ok) onAcknowledged() else failed = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = !submitting
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("I understand")
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenSupport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Contact @support") }

            if (failed) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDefer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Continue without confirming",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NoticeBullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "•  ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
    }
}

/**
 * Names a restricted feature. Falls back to the raw key rather than dropping
 * it: a key this version has never seen is still information the user needs,
 * and the console will add keys after this build has shipped.
 */
private fun featureLabel(key: String): String = when (key) {
    "send_message", "messages" -> "Sending messages is disabled."
    "create_channel", "channels" -> "Creating channels is disabled."
    "channel_post", "posts" -> "Posting to your channels is disabled."
    "media", "media_upload" -> "Sending photos, video and voice is disabled."
    "profile_edit" -> "Editing your profile is disabled."
    else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() } + " is disabled."
}

/** As [BanScreen]: device locale and timezone, null on anything unparseable. */
private fun formatNoticeTimestamp(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return try {
        val instant = Instant.parse(raw.replace(" ", "T").let {
            if (it.endsWith("Z") || it.contains("+")) it else "${it}Z"
        })
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
            .format(Date.from(instant))
    } catch (e: Exception) {
        null
    }
}
