package com.pocketledger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pocketledger.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketledger.data.CategoryTotal
import com.pocketledger.ui.design.Forest
import com.pocketledger.ui.design.Ink
import com.pocketledger.ui.design.Tints
import com.pocketledger.ui.format.categoryLabel
import com.pocketledger.ui.format.ksh
import com.pocketledger.ui.format.limitPercent
import java.util.Locale

/**
 * The forest hero: today's total, the context line, and the daily-limit bar
 * when a limit is set.
 */
@Composable
fun HeroCard(
    spentCents: Long,
    paymentCount: Int,
    balanceCents: Long?,
    dailyLimitCents: Long,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Forest.forest,
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(
                text = stringResource(R.string.home_spent_today),
                style = MaterialTheme.typography.labelMedium,
                color = Forest.mintText,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = ksh(spentCents),
                style = MaterialTheme.typography.displayMedium,
                color = Ink.surface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = contextLine(paymentCount, balanceCents),
                style = MaterialTheme.typography.bodyMedium,
                color = Forest.mintText,
            )
            if (dailyLimitCents > 0) {
                Spacer(Modifier.height(18.dp))
                LimitBar(spentCents, dailyLimitCents)
            }
        }
    }
}

@Composable
private fun contextLine(paymentCount: Int, balanceCents: Long?): String {
    val payments = when (paymentCount) {
        0 -> stringResource(R.string.home_no_payments)
        1 -> stringResource(R.string.home_one_payment)
        else -> stringResource(R.string.home_many_payments, paymentCount)
    }
    return if (balanceCents == null) {
        payments
    } else {
        stringResource(R.string.home_with_balance, payments, ksh(balanceCents))
    }
}

@Composable
private fun LimitBar(spentCents: Long, limitCents: Long) {
    val fraction = (spentCents.toFloat() / limitCents.toFloat()).coerceIn(0f, 1f)
    val percent = limitPercent(spentCents, limitCents)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Forest.soft),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Forest.mint),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.home_limit_caption, percent, ksh(limitCents)),
        style = MaterialTheme.typography.bodySmall,
        color = Forest.mintText,
    )
}

/**
 * Amber, calm, and only present while something is queued. A queued job is
 * normal; it never borrows the vocabulary of an error.
 */
@Composable
fun WaitingStrip(count: Int, modifier: Modifier = Modifier) {
    val visible = count > 0
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Tints.amberTint,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Tints.amber),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (count == 1) {
                        stringResource(R.string.waiting_one)
                    } else {
                        stringResource(R.string.waiting_many, count)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Tints.amberInk,
                )
            }
        }
    }
}

/** One of the top three categories today. */
@Composable
fun CategoryTile(total: CategoryTotal, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Ink.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Ink.line),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = categoryLabel(total.category),
                style = MaterialTheme.typography.bodySmall,
                color = Ink.muted,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = ksh(total.amountCents),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink.ink,
                maxLines = 1,
            )
        }
    }
}

/** Notification access is off: persistent, with one way back. */
@Composable
fun AccessBanner(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Ink.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Ink.line),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.access_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.ink,
                )
                Text(
                    text = stringResource(R.string.access_banner_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.muted,
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text(
                    text = stringResource(R.string.access_turn_on),
                    style = MaterialTheme.typography.labelMedium,
                    color = Forest.green,
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = Ink.muted,
        modifier = modifier,
    )
}

@Composable
fun CategoryTileRow(totals: List<CategoryTotal>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        totals.take(3).forEach { total ->
            CategoryTile(total, Modifier.weight(1f))
        }
    }
}
