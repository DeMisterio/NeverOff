package com.neveroff.ui.components

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.neveroff.data.remote.ReportRepository
import kotlinx.coroutines.launch

/**
 * The reporting flow (§6.1–6.2).
 *
 * Three steps: pick a category, pick a specific reason, then add detail. The
 * two-level taxonomy exists because "Spam" alone tells a moderator almost
 * nothing, and a single flat list of thirty reasons is worse than useless on a
 * phone.
 *
 * The step that matters most is the third one. NeverOff messages are
 * end-to-end encrypted, so unless the reporter chooses to include the text,
 * nobody reviewing this will ever see what was actually said. That is a real
 * decision with real consequences, so it is presented as one — an explicit,
 * off-by-default choice with the consequence spelled out, rather than a
 * pre-ticked box nobody reads.
 */
@Composable
fun ReportDialog(
    targetType: ReportRepository.TargetType,
    targetId: String,
    /** Shown so the reporter can see exactly what they are reporting. */
    targetLabel: String,
    /**
     * Decrypted content this device holds, when there is any. Null for a
     * profile or channel report, where there is nothing to volunteer.
     */
    availableEvidence: String? = null,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var category by remember { mutableStateOf<ReportRepository.Category?>(null) }
    var reason by remember { mutableStateOf<ReportRepository.Reason?>(null) }
    var note by remember { mutableStateOf("") }
    var shareEvidence by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf(ReportRepository.OutcomePreference.JUST_REPORT) }

    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var receipt by remember { mutableStateOf<ReportRepository.Receipt?>(null) }

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .padding(20.dp)
            ) {
                // --- Header ---------------------------------------------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (receipt == null && (category != null)) {
                        IconButton(
                            onClick = {
                                if (reason != null) reason = null else category = null
                                error = null
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = when {
                            receipt != null -> "Report sent"
                            reason != null -> "Add detail"
                            category != null -> category!!.label
                            else -> "Report"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (receipt == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = targetLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // --- Body -----------------------------------------------------
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    when {
                        // ---------- Confirmation ----------
                        receipt != null -> {
                            Text(
                                text = receipt!!.message,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (receipt!!.routing == "safety_escalation") {
                                Spacer(Modifier.height(16.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Text(
                                        text = "If someone is in immediate danger, please contact " +
                                            "your local emergency services. They can help far " +
                                            "faster than we can.",
                                        modifier = Modifier.padding(14.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        // ---------- Step 1: category ----------
                        category == null -> {
                            ReportRepository.CATEGORIES.forEach { item ->
                                ChoiceRow(item.label) { category = item }
                            }
                        }

                        // ---------- Step 2: specific reason ----------
                        reason == null -> {
                            category!!.reasons.forEach { item ->
                                ChoiceRow(item.label) { reason = item }
                            }
                        }

                        // ---------- Step 3: detail ----------
                        else -> {
                            Text(
                                text = reason!!.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(Modifier.height(12.dp))

                            OutlinedTextField(
                                value = note,
                                onValueChange = { if (it.length <= 2000) note = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.anything_else_we_should_know_optional)) },
                                minLines = 3,
                                shape = RoundedCornerShape(12.dp)
                            )

                            // The consent step. Only offered when this device
                            // actually holds something to share.
                            if (availableEvidence != null) {
                                Spacer(Modifier.height(16.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.Top) {
                                            Checkbox(
                                                checked = shareEvidence,
                                                onCheckedChange = { shareEvidence = it }
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Column {
                                                Text(
                                                    text = "Include the message so we can read it",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = "Your messages are end-to-end " +
                                                        "encrypted, so we cannot see them. If you " +
                                                        "do not include it, we can still act on " +
                                                        "patterns of behaviour, but nobody will " +
                                                        "be able to read what was sent.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme
                                                        .onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "What would you like to happen?",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))

                            val options = buildList {
                                add(ReportRepository.OutcomePreference.JUST_REPORT)
                                if (targetType == ReportRepository.TargetType.USER ||
                                    targetType == ReportRepository.TargetType.MESSAGE
                                ) {
                                    add(ReportRepository.OutcomePreference.BLOCK_USER)
                                }
                                if (targetType == ReportRepository.TargetType.CHANNEL ||
                                    targetType == ReportRepository.TargetType.CHANNEL_POST
                                ) {
                                    add(ReportRepository.OutcomePreference.LEAVE_CHANNEL)
                                }
                            }

                            options.forEach { option ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { outcome = option }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = outcome == option,
                                        onClick = { outcome = option }
                                    )
                                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Reports are anonymous. The person you are reporting is " +
                                    "never told who reported them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // --- Footer ---------------------------------------------------
                Spacer(Modifier.height(16.dp))

                if (receipt != null) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.done)) }
                } else if (reason != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !submitting
                        ) { Text(stringResource(R.string.action_cancel)) }

                        Button(
                            onClick = {
                                submitting = true
                                error = null
                                scope.launch {
                                    try {
                                        receipt = ReportRepository.submitReport(
                                            targetType = targetType,
                                            targetId = targetId,
                                            reasonCode = reason!!.code,
                                            note = note.trim().ifBlank { null },
                                            outcomePreference = outcome,
                                            evidenceText =
                                                if (shareEvidence) availableEvidence else null,
                                            contextConsented = false
                                        )
                                    } catch (e: Exception) {
                                        error = e.message
                                            ?: "Could not send that report. Please try again."
                                    } finally {
                                        submitting = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !submitting
                        ) {
                            if (submitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.send_report))
                            }
                        }
                    }
                } else {
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}
