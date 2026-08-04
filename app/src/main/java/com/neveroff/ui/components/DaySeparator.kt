package com.neveroff.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neveroff.R
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The little date pill that sits between days in a conversation.
 *
 * Every messenger has one because a timestamp alone is ambiguous: "14:32" tells
 * you nothing about whether that was this afternoon or last March, and scrolling
 * back through a long history without them is disorienting.
 */

/**
 * A stable key for "which calendar day is this timestamp in".
 *
 * Compared as year-and-day-of-year rather than by dividing the epoch by 86400000,
 * which would be wrong for anyone not on UTC and would move the boundary around
 * with daylight saving. The day changes at local midnight, which is the only
 * definition a reader would recognise.
 */
fun dayKeyOf(timestamp: Long): Int {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
}

/** True when [timestamp] falls on a different local day than [previous]. */
fun startsNewDay(timestamp: Long, previous: Long?): Boolean =
    previous == null || dayKeyOf(timestamp) != dayKeyOf(previous)

/**
 * "Today", "Yesterday", or a date.
 *
 * Older entries include the year only when it is not the current one — "3 March"
 * reads better than "3 March 2026" for something from this year, and the year
 * matters exactly when it differs.
 */
@Composable
fun dayLabel(timestamp: Long): String {
    val today = dayKeyOf(System.currentTimeMillis())
    val yesterday = dayKeyOf(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
    return when (dayKeyOf(timestamp)) {
        today -> stringResource(R.string.day_today)
        yesterday -> stringResource(R.string.day_yesterday)
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
            val thisYear = Calendar.getInstance().get(Calendar.YEAR)
            val pattern = if (cal.get(Calendar.YEAR) == thisYear) "d MMMM" else "d MMMM yyyy"
            // Locale.getDefault() is the app's locale here, because MainActivity
            // overrides it in the composition — so the month name follows the
            // language the user picked rather than the device's.
            java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
        }
    }
}

@Composable
fun DaySeparator(timestamp: Long, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dayLabel(timestamp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    // Sits over chat wallpapers of any colour, so it needs its
                    // own backing rather than relying on the surface behind it.
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(50)
                )
                .padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}
