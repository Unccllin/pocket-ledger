package com.pocketledger.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pocketledger.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketledger.data.LedgerRow
import com.pocketledger.data.LedgerState
import com.pocketledger.ui.components.ActivityFilter
import com.pocketledger.ui.components.FilterChip
import com.pocketledger.ui.components.ReviewRow
import com.pocketledger.ui.components.SectionLabel
import com.pocketledger.ui.components.TransactionRow
import com.pocketledger.ui.components.WaitingRow
import com.pocketledger.ui.design.Ink
import com.pocketledger.ui.format.dayHeading

/**
 * Activity: every confirmation, grouped by day, with the three row states the
 * design asks for (done, waiting, needs review).
 */
@Composable
fun ActivityScreen(
    state: LedgerState,
    filter: ActivityFilter,
    onFilter: (ActivityFilter) -> Unit,
    onOpenRow: (LedgerRow) -> Unit,
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.latest.filter { row ->
        when (filter) {
            ActivityFilter.All -> true
            ActivityFilter.Spent -> !row.isReceived
            ActivityFilter.Received -> row.isReceived
            ActivityFilter.Review -> false
        }
    }
    val showWaiting = state.waiting > 0 && filter != ActivityFilter.Received
    val showReview = state.review.isNotEmpty() &&
        (filter == ActivityFilter.All || filter == ActivityFilter.Review)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.activity_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Ink.ink,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActivityFilter.entries.forEach { option ->
                    FilterChip(
                        label = stringResource(option.labelRes),
                        selected = option == filter,
                        onClick = { onFilter(option) },
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        if (showReview) {
            item {
                SectionLabel(stringResource(R.string.activity_needs_look))
                Spacer(Modifier.height(6.dp))
                ReviewRow(count = state.review.size, onClick = onOpenReview, showDivider = showWaiting)
            }
        }
        if (showWaiting) {
            item {
                SectionLabel(stringResource(R.string.activity_reading_now))
                Spacer(Modifier.height(6.dp))
                WaitingRow(showDivider = true)
            }
        }

        // Day headings between rows, stable key per transaction code.
        val headings = visible.map { dayHeading(it.occurredAt) }
        itemsIndexed(visible, key = { _, row -> row.code }) { index, row ->
            if (index == 0 || headings[index] != headings[index - 1]) {
                Spacer(Modifier.height(if (index == 0) 4.dp else 18.dp))
                SectionLabel(headings[index])
                Spacer(Modifier.height(6.dp))
            }
            TransactionRow(
                row = row,
                onClick = { onOpenRow(row) },
                showDivider = index != visible.lastIndex,
            )
        }

        if (visible.isEmpty() && !showWaiting && !showReview) {
            item {
                Text(
                    text = stringResource(
                        when (filter) {
                            ActivityFilter.Received -> R.string.activity_empty_received
                            ActivityFilter.Review -> R.string.activity_empty_review
                            else -> R.string.activity_empty_all
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.muted,
                    modifier = Modifier.padding(top = 26.dp),
                )
            }
        }
    }
}
