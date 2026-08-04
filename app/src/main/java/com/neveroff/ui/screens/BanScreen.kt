package com.neveroff.ui.screens

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neveroff.data.remote.EnforcementRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date

/**
 * The §4 blocking screen.
 *
 * Shown instead of the app when the account is suspended. Three requirements
 * drive the design:
 *
 *   - It must say *why*, in the user's own language, citing the specific policy
 *     clause. A generic "your account has been suspended" gives someone no way
 *     to understand what happened or to argue with it.
 *   - It must show the exact expiry for a temporary suspension. "Try again
 *     later" is not an answer.
 *   - Appealing must be possible from here. This screen is the only surface a
 *     blocked user can reach, so if the appeal path is not on it, there is no
 *     appeal path.
 *
 * Deliberately not dismissible and with no back navigation: the calling
 * NavHost entry replaces the whole graph rather than stacking on top of it.
 */
@Composable
fun BanScreen(
    status: EnforcementRepository.Status,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    /**
     * Opens the in-app @support surface. §4 requires this be reachable while
     * suspended — a link out to a web page is not the same thing, because a
     * suspended user has no session there and nothing ties the conversation to
     * their account.
     */
    onOpenSupport: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var showAppealForm by remember { mutableStateOf(false) }
    var appealText by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var appealState by remember(status.appeal?.state) {
        mutableStateOf(status.appeal?.state ?: "none")
    }

    val expiryText = remember(status.expiresAt) { formatTimestamp(status.expiresAt) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                text = if (status.isPermanent) "Account permanently suspended"
                       else "Account suspended",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (status.isPermanent) {
                    "This suspension does not expire."
                } else if (expiryText != null) {
                    "Your access will be restored automatically on $expiryText."
                } else {
                    "Your access will be restored when this suspension ends."
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

            Spacer(Modifier.height(16.dp))

            // --- Case reference ----------------------------------------------
            status.caseRef?.let { caseRef ->
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
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Quote this if you contact support.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // --- Appeal -------------------------------------------------------
            when {
                appealState != "none" -> AppealStatusCard(appealState, status.appeal?.slaDueAt)

                status.canAppeal && !showAppealForm -> {
                    Button(
                        onClick = { showAppealForm = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.appeal_this_decision))
                    }
                }

                status.canAppeal && showAppealForm -> {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = "Tell us what we got wrong. Someone other than the person " +
                                "who made this decision will read it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = appealText,
                            onValueChange = {
                                if (it.length <= 5000) {
                                    appealText = it
                                    submitError = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                            placeholder = { Text(stringResource(R.string.explain_what_happened)) },
                            supportingText = { Text("${appealText.length} / 5000") },
                            isError = submitError != null,
                            shape = RoundedCornerShape(14.dp)
                        )

                        submitError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { showAppealForm = false; submitError = null },
                                modifier = Modifier.weight(1f).height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                enabled = !submitting
                            ) { Text(stringResource(R.string.action_cancel)) }

                            Button(
                                onClick = {
                                    val actionId = status.actionId
                                    if (actionId == null) {
                                        submitError = "This decision cannot be appealed from here."
                                        return@Button
                                    }
                                    if (appealText.trim().length < 20) {
                                        submitError =
                                            "Please give us a bit more detail — at least a sentence."
                                        return@Button
                                    }
                                    submitting = true
                                    scope.launch {
                                        try {
                                            EnforcementRepository.submitAppeal(
                                                actionId, appealText.trim()
                                            )
                                            appealState = "pending"
                                            showAppealForm = false
                                        } catch (e: Exception) {
                                            submitError = e.message
                                                ?: "Could not submit right now. Try again shortly."
                                        } finally {
                                            submitting = false
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(50.dp),
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
                                    Text(stringResource(R.string.submit_appeal))
                                }
                            }
                        }
                    }
                }

                else -> {
                    Text(
                        text = "Decisions of this type cannot be appealed in the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
            Spacer(Modifier.height(24.dp))

            // §4: @support stays reachable while every other surface is locked.
            // It runs on SECURITY DEFINER RPCs that never consult the
            // enforcement predicates, so a suspended account can still open a
            // conversation and be answered in it.
            OutlinedButton(
                onClick = onOpenSupport,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.contact_support)) }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.check_again))
            }

            TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.sign_out),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppealStatusCard(state: String, slaDueAt: String?) {
    val (title, detail) = when (state) {
        "pending" -> "Appeal received" to
            "Someone other than the person who made this decision will review it."
        "under_review" -> "Appeal under review" to "A reviewer is looking at your case now."
        "upheld" -> "Appeal reviewed" to
            "The original decision stands. The reason above still applies."
        "overturned" -> "Appeal upheld" to
            "The decision was reversed. Your access should return shortly — tap Check again."
        "partially_upheld" -> "Appeal partly upheld" to
            "Part of the decision was changed. Tap Check again for your current status."
        "withdrawn" -> "Appeal withdrawn" to "You withdrew this appeal."
        else -> "Appeal submitted" to "We will get back to you."
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            if (state == "pending" || state == "under_review") {
                formatTimestamp(slaDueAt)?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "We aim to respond by $it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * Render an ISO-8601 timestamp in the device's own locale and timezone.
 * Returns null on anything unparseable so the caller can fall back to prose
 * rather than showing a broken date on the one screen the user cannot leave.
 */
private fun formatTimestamp(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return try {
        val instant = Instant.parse(raw.replace(" ", "T").let {
            if (it.endsWith("Z") || it.contains("+")) it else "${it}Z"
        })
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
            .format(Date.from(instant))
    } catch (e: DateTimeParseException) {
        null
    } catch (e: Exception) {
        null
    }
}
