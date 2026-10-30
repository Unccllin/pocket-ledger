@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pocketledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pocketledger.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketledger.data.LedgerRow
import com.pocketledger.data.ReviewItem
import com.pocketledger.ui.design.Forest
import com.pocketledger.ui.design.Ink
import com.pocketledger.ui.design.Tints
import com.pocketledger.ui.format.categoryLabel
import com.pocketledger.ui.format.displayName
import com.pocketledger.ui.format.ksh
import com.pocketledger.ui.format.timeLabel

/**
 * One transaction in full. The original message is behind a toggle because it
 * is the least useful thing on the sheet until someone needs to check it, and
 * it is the only place a masked phone number could appear.
 */
@Composable
fun DetailsSheet(row: LedgerRow, onDismiss: () -> Unit) {
    // Half-height first, so the Home context stays visible; drag for more.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showOriginal by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
        ) {
            Text(
                text = displayName(row.counterparty),
                style = MaterialTheme.typography.headlineMedium,
                color = Ink.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${categoryLabel(row.category)} \u00B7 ${timeLabel(row.occurredAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.muted,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (row.isReceived) "+${ksh(row.amountCents)}" else ksh(row.amountCents),
                style = MaterialTheme.typography.displayMedium,
                color = if (row.isReceived) Forest.green else Ink.ink,
            )
            Spacer(Modifier.height(18.dp))
            DetailRow(stringResource(R.string.details_code), row.code)
            row.balanceCents?.let { DetailRow(stringResource(R.string.details_balance_after), ksh(it)) }
            if (row.rawText != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { showOriginal = !showOriginal }) {
                    Text(
                        text = stringResource(
                            if (showOriginal) R.string.details_hide_original else R.string.details_show_original
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Forest.green,
                    )
                }
                if (showOriginal) {
                    Surface(
                        color = Ink.ground,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(
                                text = row.rawText.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.muted,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.details_close),
                    style = MaterialTheme.typography.labelMedium,
                    color = Forest.green,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = Ink.ink,
        )
    }
}

/**
 * The review flow: the message as it arrived, and exactly two honest choices.
 * Retry re-queues it; Ignore marks it ignored. Nothing is deleted.
 */
@Composable
fun ReviewSheet(
    item: ReviewItem,
    remaining: Int,
    onRetry: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.review_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Ink.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.review_body) + " " +
                    if (remaining > 1) stringResource(R.string.review_body_more, remaining) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.muted,
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                color = Tints.amberTint,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = item.rawText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tints.amberInk,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onRetry,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.review_retry),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                OutlinedButton(
                    onClick = onIgnore,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.review_ignore),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (item.attempts == 1) {
                    stringResource(R.string.review_tried_one)
                } else {
                    stringResource(R.string.review_tried, item.attempts)
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ink.muted,
                textAlign = TextAlign.Start,
            )
        }
    }
}
