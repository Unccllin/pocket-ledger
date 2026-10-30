package com.pocketledger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The consumer screen: what you spent today, and the messages behind it.
 *
 * The screen refreshes itself while it is visible (faster while jobs are still
 * running) so a confirmation that arrives while the app is open appears without
 * a manual pull.
 */
@Composable
fun LedgerApp() {
    val context = LocalContext.current
    var stats by remember { mutableStateOf<LedgerStats?>(null) }
    var accessGranted by remember { mutableStateOf(notificationAccessGranted(context)) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            refreshing = true
            stats = loadStats(context)
            accessGranted = notificationAccessGranted(context)
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            stats = loadStats(context)
            accessGranted = notificationAccessGranted(context)
            delay(if (stats?.hasWork == true) 800L else 2500L)
        }
    }

    if (showDiagnostics) {
        BackHandler { showDiagnostics = false }
        DiagnosticsScreen(
            stats = stats,
            onBack = { showDiagnostics = false },
            onRefresh = { refresh() },
        )
    } else {
        HomeScreen(
            stats = stats,
            accessGranted = accessGranted,
            refreshing = refreshing,
            onRefresh = { refresh() },
            onOpenDiagnostics = { showDiagnostics = true },
            onEnableAccess = {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            },
        )
    }
}

@Composable
private fun HomeScreen(
    stats: LedgerStats?,
    accessGranted: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onEnableAccess: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Header(
                    refreshing = refreshing,
                    onRefresh = onRefresh,
                    onOpenDiagnostics = onOpenDiagnostics,
                )
            }

            item { SpendCard(stats) }

            if (!accessGranted) {
                item { AccessBanner(onEnableAccess) }
            }

            if (stats != null && (stats.hasWork || stats.needsAttention)) {
                item { StatusPill(stats, onOpenDiagnostics) }
            }

            item { SectionHeader(stats?.latest?.size ?: 0, stats?.ledgerRows ?: 0) }

            val snapshot = stats
            if (snapshot == null) {
                item { LoadingBlock() }
            } else if (snapshot.latest.isEmpty()) {
                item { EmptyState(accessGranted, onEnableAccess) }
            } else {
                itemsIndexed(snapshot.latest, key = { _, row -> row.code }) { index, row ->
                    TransactionRow(row, showDivider = index != snapshot.latest.lastIndex)
                }
            }

            item { PrivacyFooter() }
        }
    }
}

@Composable
private fun Header(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "pocket-ledger",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.4).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "M-Pesa, logged automatically",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenDiagnostics) {
            Icon(
                Icons.Filled.Info,
                contentDescription = "Diagnostics",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpendCard(stats: LedgerStats?) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = colors.primaryContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SPENT TODAY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.6.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Spacer(Modifier.weight(1f))
                OfflineBadge()
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "Ksh",
                    fontSize = 18.sp,
                    color = colors.onPrimaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.padding(bottom = 9.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = money(stats?.todaySpendCents ?: 0L),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1.2).sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            val income = stats?.todayIncomeCents ?: 0L
            Text(
                text = if (income > 0) {
                    "Ksh${money(income)} received today"
                } else {
                    "Nothing received today"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (income > 0) {
                    ReceivedGreen
                } else {
                    colors.onPrimaryContainer.copy(alpha = 0.65f)
                },
            )
        }
    }
}

@Composable
private fun OfflineBadge() {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.primary)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "on-device",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun AccessBanner(onEnableAccess: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Turn on notification access",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "pocket-ledger only reads M-Pesa confirmations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onEnableAccess) { Text("Enable") }
        }
    }
}

@Composable
private fun StatusPill(stats: LedgerStats, onOpenDiagnostics: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val attention = stats.needsAttention
    Surface(shape = CircleShape, color = colors.surfaceVariant) {
        Row(
            Modifier.padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (attention) colors.error else colors.primary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    stats.hasWork -> "${stats.queued + stats.running} being processed"
                    attention -> "${stats.failed + stats.unparsed} need review"
                    else -> "Up to date"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            TextButton(onClick = onOpenDiagnostics) { Text("Details") }
        }
    }
}

@Composable
private fun SectionHeader(shown: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Recent",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        if (total > 0) {
            Text(
                // stats ships the newest 20 rows; do not claim the list is the
                // whole ledger once it is longer than that.
                text = when {
                    shown < total -> "$shown of $total messages"
                    total == 1 -> "1 message"
                    else -> "$total messages"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransactionRow(row: LedgerRowModel, showDivider: Boolean) {
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(categoryTint(row.category, dark)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = categoryEmoji(row.category), fontSize = 18.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.counterparty,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${categoryLabel(row.category)} \u00B7 ${timeLabel(row.occurredAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = signedAmount(row.amountCents, row.isReceived),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
                color = if (row.isReceived) ReceivedGreen else colors.onSurface,
            )
        }
        if (showDivider) {
            HorizontalDivider(color = colors.outlineVariant, thickness = 1.dp)
        }
    }
}

@Composable
private fun EmptyState(accessGranted: Boolean, onEnableAccess: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "\uD83D\uDCEC", fontSize = 26.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No M-Pesa messages yet",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "When M-Pesa confirms a payment, it lands here automatically. " +
                "Nothing leaves this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (!accessGranted) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onEnableAccess) { Text("Turn on notification access") }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Text(
        text = "Reading your ledger\u2026",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

@Composable
private fun PrivacyFooter() {
    Text(
        text = "Stays on this phone \u00B7 no internet access",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}
