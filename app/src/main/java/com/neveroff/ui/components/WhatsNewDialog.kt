package com.neveroff.ui.components

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One parsed line of a release note.
 *
 * Release descriptions are authored as plain text in the `versions` table, so structure is
 * inferred: a short line ending in ':' reads as a section heading, a line starting with a
 * bullet/dash is an item, and everything else is a paragraph.
 */
private sealed interface NoteLine {
    data class Heading(val text: String) : NoteLine
    data class Bullet(val text: String) : NoteLine
    data class Paragraph(val text: String) : NoteLine
}

private fun parseNotes(description: String): List<NoteLine> =
    description.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            when {
                line.startsWith("•") || line.startsWith("-") || line.startsWith("*") ->
                    NoteLine.Bullet(line.removePrefix("•").removePrefix("-").removePrefix("*").trim())
                line.endsWith(":") && line.length <= 48 -> NoteLine.Heading(line.dropLast(1))
                else -> NoteLine.Paragraph(line)
            }
        }

@Composable
fun WhatsNewDialog(
    version: String,
    description: String,
    onDismiss: () -> Unit
) {
    val lines = parseNotes(description)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.what_s_new_2),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Version $version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        },
        text = {
            // Release notes grow over time; without this a long list overflows the dialog.
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                lines.forEachIndexed { index, line ->
                    when (line) {
                        is NoteLine.Heading -> {
                            if (index > 0) Spacer(Modifier.height(14.dp))
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(6.dp))
                        }

                        is NoteLine.Bullet -> {
                            Row(
                                modifier = Modifier.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 7.dp, end = 10.dp)
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Text(
                                    text = line.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        is NoteLine.Paragraph -> {
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
