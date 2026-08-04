package com.neveroff.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neveroff.R
import com.neveroff.data.remote.EnforcementState
import com.neveroff.data.remote.rememberEnforcement

/**
 * Rendering restrictions issued by the moderation console.
 *
 * The rule everywhere in here: a restricted control stays *visible* and goes
 * grey, rather than disappearing. A vanished button reads as a broken app and
 * sends people to support; a greyed one that explains itself when tapped reads
 * as a consequence, which is what it is. §4 asks for the user to be told what
 * applies to them, and a control that silently does nothing tells them nothing.
 */

/** Human name for a feature key, translated. Unknown keys fall back to the key. */
@Composable
fun featureLabel(feature: String): String = when (feature) {
    EnforcementState.Feature.SEND_MESSAGE -> stringResource(R.string.feature_send_message)
    EnforcementState.Feature.SEND_VOICE_NOTE -> stringResource(R.string.feature_send_voice_note)
    EnforcementState.Feature.SEND_IMAGE -> stringResource(R.string.feature_send_image)
    EnforcementState.Feature.SEND_VIDEO -> stringResource(R.string.feature_send_video)
    EnforcementState.Feature.CREATE_GROUP -> stringResource(R.string.feature_create_group)
    EnforcementState.Feature.CREATE_CHANNEL -> stringResource(R.string.feature_create_channel)
    EnforcementState.Feature.SUBSCRIBE_CHANNEL -> stringResource(R.string.feature_subscribe_channel)
    EnforcementState.Feature.REACT_TO_POST -> stringResource(R.string.feature_react_to_post)
    EnforcementState.Feature.SEARCH_USERS -> stringResource(R.string.feature_search_users)
    EnforcementState.Feature.EDIT_PROFILE -> stringResource(R.string.feature_edit_profile)
    EnforcementState.Feature.CHANGE_WALLPAPER -> stringResource(R.string.feature_change_wallpaper)
    EnforcementState.Feature.SCHEDULE_MESSAGE -> stringResource(R.string.feature_schedule_message)
    // A feature added to the console after this version shipped. Showing the raw
    // key is ugly but honest; showing nothing would hide a real restriction.
    else -> feature
}

/**
 * True when [feature] is currently blocked.
 *
 * Recomposes when enforcement changes, so a restriction lifted from the console
 * re-enables the control on the next status poll without a restart.
 */
@Composable
fun isFeatureRestricted(feature: String): Boolean {
    // Read the state so this composable subscribes to it; the decision itself
    // lives in EnforcementState so non-Compose callers get the same answer.
    val status by rememberEnforcement()
    return status.blocksAccess ||
        status.restrictedFeatures.contains(feature) ||
        EnforcementState.isRestricted(feature)
}

/**
 * Greys out its content and swallows taps when [feature] is restricted.
 *
 * Wrapping rather than replacing keeps the layout identical either way, so the
 * screen does not reflow when a restriction lands or lifts.
 */
@Composable
fun RestrictionGate(
    feature: String,
    modifier: Modifier = Modifier,
    content: @Composable (enabled: Boolean) -> Unit
) {
    val restricted = isFeatureRestricted(feature)
    val label = featureLabel(feature)
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier.then(
            if (restricted) {
                // The tap must be consumed here, not merely ignored: without
                // something on top claiming it, a press falls through to the
                // control underneath and the action happens anyway.
                Modifier
                    .alpha(0.38f)
                    .clickable(interactionSource = interaction, indication = null) {
                        AppToast.show(
                            context.getString(R.string.restriction_blocked_toast, label)
                        )
                    }
            } else Modifier
        )
    ) {
        content(!restricted)
    }
}

/**
 * The standing list of what currently applies to this account.
 *
 * Renders nothing at all when there is nothing to say, so it can be dropped into
 * Settings unconditionally without adding an empty section for the overwhelming
 * majority of users who have never been actioned.
 *
 * Shadow restriction never appears here. [EnforcementState.activeRestrictions]
 * excludes it, and that is the single place the exclusion is enforced.
 */
@Composable
fun AccountRestrictionsCard(modifier: Modifier = Modifier) {
    val status by rememberEnforcement()
    val restrictions = EnforcementState.activeRestrictions()
    if (restrictions.isEmpty() && !status.blocksSending) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.restriction_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        Text(
            text = stringResource(R.string.restriction_active_header),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )

        restrictions.forEach { feature ->
            Text(
                text = "•  ${featureLabel(feature)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        // The reason arrives already localised from the console's catalogue, so
        // a reason code added after this version shipped still reads as text.
        status.reason?.label?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                text = "${stringResource(R.string.restriction_reason)}: $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        Text(
            text = status.expiresAt
                ?.let { stringResource(R.string.restriction_until, formatRestrictionDate(it)) }
                ?: stringResource(R.string.restriction_permanent),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer
        )

        status.caseRef?.takeIf { it.isNotBlank() }?.let { ref ->
            Text(
                text = "${stringResource(R.string.restriction_case_ref)}: $ref",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** Server timestamps are ISO-8601; fall back to the raw value rather than crashing. */
private fun formatRestrictionDate(iso: String): String = try {
    val instant = java.time.Instant.parse(iso)
    java.time.format.DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
} catch (e: Exception) {
    iso
}

/**
 * Banner shown above a composer the user is currently barred from using.
 *
 * Deliberately not a dialog: a dialog would have to be dismissed every time the
 * chat is opened, and this is a standing condition rather than an event.
 */
@Composable
fun SendingRestrictedBanner(
    modifier: Modifier = Modifier,
    onSeeDetails: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
        Text(
            text = stringResource(R.string.restriction_sending_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        if (onSeeDetails != null) {
            TextButton(onClick = onSeeDetails) {
                Text(
                    text = stringResource(R.string.restriction_see_details),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
