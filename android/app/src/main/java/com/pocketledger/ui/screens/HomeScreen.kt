package com.pocketledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pocketledger.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketledger.data.LedgerRow
import com.pocketledger.data.LedgerState
import com.pocketledger.ui.components.AccessBanner
import com.pocketledger.ui.components.CategoryTileRow
import com.pocketledger.ui.components.HeroCard
import com.pocketledger.ui.components.SectionLabel
import com.pocketledger.ui.components.TransactionRow
import com.pocketledger.ui.components.WaitingStrip
import com.pocketledger.ui.design.Forest
import com.pocketledger.ui.design.Ink
import com.pocketledger.ui.format.todayLabel

/**
 * Home: the day's result first, then what it was made of.
 *
 * Layout order follows `docs/DESIGN.md`: date + title, hero, waiting strip,
 * category tiles, the three latest rows. Everything technical lives behind the
 * Settings tab.
 */
@Composable
fun HomeScreen(
    state: LedgerState,
    dailyLimitCents: Long,
    accessGranted: Boolean,
    onOpenRow: (LedgerRow) -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    onGrantAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = todayLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = Ink.muted,
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = Ink.ink,
            )
        }

        item {
            HeroCard(
                spentCents = state.spentTodayCents,
                paymentCount = state.paymentCount,
                balanceCents = state.balanceCents,
                dailyLimitCents = dailyLimitCents,
            )
        }

        if (!accessGranted) {
            item { AccessBanner(onOpenSettings = onGrantAccess) }
        }

        item { WaitingStrip(state.waiting) }

        if (state.topCategories.isNotEmpty()) {
            item {
                SectionLabel(stringResource(R.string.home_where_went))
                Spacer(Modifier.height(10.dp))
                CategoryTileRow(state.topCategories)
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.home_latest), Modifier.weight(1f))
                TextButton(onClick = onOpenActivity) {
                    Text(
                        text = stringResource(R.string.home_see_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = Forest.green,
                    )
                }
            }
        }

        if (state.latest.isEmpty()) {
            item { EmptyHome(accessGranted = accessGranted) }
        } else {
            items(state.latest.take(3), key = { it.code }) { row ->
                TransactionRow(row = row, onClick = { onOpenRow(row) })
            }
        }

        item {
            Text(
                text = stringResource(R.string.home_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = Ink.muted,
            )
        }
    }
}

@Composable
private fun EmptyHome(accessGranted: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = Ink.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (accessGranted) R.string.home_empty_body else R.string.home_empty_body_no_access
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}
