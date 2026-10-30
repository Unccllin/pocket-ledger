package com.pocketledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pocketledger.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.pocketledger.data.LedgerRow
import com.pocketledger.ui.design.Forest
import com.pocketledger.ui.design.Ink
import com.pocketledger.ui.design.Tints
import com.pocketledger.ui.format.categoryLabel
import com.pocketledger.ui.format.displayName
import com.pocketledger.ui.format.initials
import com.pocketledger.ui.format.ksh
import com.pocketledger.ui.format.timeLabel

/** 40dp, rounded 12, initials only: no icons and no emoji anywhere. */
@Composable
fun InitialsAvatar(
    text: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = contentColor,
            // The avatar is a graphic: hold its glyph at 14dp even when the
            // system font scale grows, so initials never spill out of the tile.
            fontSize = with(LocalDensity.current) { 14.dp.toSp() },
        )
    }
}

/**
 * One row in any of its three states. The whole row is one tap target and one
 * semantic node, so TalkBack reads a single sentence instead of four labels.
 */
@Composable
fun LedgerRowItem(
    title: String,
    subtitle: String,
    avatarText: String,
    avatarBackground: Color,
    avatarContent: Color,
    amountText: String,
    amountColor: Color,
    semanticLabel: String,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = semanticLabel
                    role = Role.Button
                }
                .heightIn(min = 64.dp)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialsAvatar(avatarText, avatarBackground, avatarContent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.padding(top = 1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = amountText,
                style = MaterialTheme.typography.labelLarge,
                color = amountColor,
                maxLines = 2,
            )
        }
        if (showDivider) {
            HorizontalDivider(color = Ink.line, thickness = 1.dp)
        }
    }
}

/** A finished transaction. */
@Composable
fun TransactionRow(
    row: LedgerRow,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    val received = row.isReceived
    val category = categoryLabel(row.category)
    val time = timeLabel(row.occurredAt)
    LedgerRowItem(
        title = displayName(row.counterparty),
        subtitle = "$category \u00B7 $time",
        avatarText = initials(row.counterparty),
        avatarBackground = if (received) Tints.blueTint else Tints.mintTint,
        avatarContent = if (received) Tints.blue else Forest.green,
        amountText = ksh(row.amountCents),
        amountColor = if (received) Forest.green else Ink.ink,
        semanticLabel = "${row.counterparty}, $category, " +
            "${if (received) "plus" else "minus"} ${plainAmount(row.amountCents)} shillings, $time",
        onClick = onClick,
        showDivider = showDivider,
    )
}

/** A message that is still being read. Normal, never an error. */
@Composable
fun WaitingRow(showDivider: Boolean = true, onClick: () -> Unit = {}) {
    LedgerRowItem(
        title = stringResource(R.string.row_reading_message),
        subtitle = stringResource(R.string.row_waiting_in_queue),
        avatarText = "\u2026",
        avatarBackground = Tints.amberTint,
        avatarContent = Tints.amberAvatarInk,
        amountText = stringResource(R.string.row_amount_pending),
        amountColor = Ink.muted,
        semanticLabel = "Reading message, waiting in queue",
        onClick = onClick,
        showDivider = showDivider,
    )
}

/** A message the parser could not read: the only state that asks for action. */
@Composable
fun ReviewRow(count: Int, onClick: () -> Unit, showDivider: Boolean = true) {
    LedgerRowItem(
        title = if (count == 1) {
            stringResource(R.string.row_review_title)
        } else {
            stringResource(R.string.row_review_title_many, count)
        },
        subtitle = stringResource(R.string.row_review_subtitle),
        avatarText = "?",
        avatarBackground = Tints.redTint,
        avatarContent = Tints.redInk,
        amountText = stringResource(R.string.row_review_action),
        amountColor = Tints.redInk,
        semanticLabel = "Review, could not read this one, tap to fix or ignore",
        onClick = onClick,
        showDivider = showDivider,
    )
}

private fun plainAmount(cents: Long): String {
    val whole = cents / 100
    val fraction = cents % 100
    return if (fraction == 0L) whole.toString() else "$whole.${fraction.toString().padStart(2, '0')}"
}
