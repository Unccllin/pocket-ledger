package com.pocketledger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketledger.LedgerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.Random

/**
 * A small, honest diagnostics screen: queue state, and the tools needed to
 * check the pipeline without leaving the app. The consumer screen stays clean;
 * everything technical lives here.
 */
@Composable
fun DiagnosticsScreen(
    stats: LedgerStats?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    Scaffold(containerColor = colors.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Diagnostics",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onBackground,
                )
            }

            StatsCard(stats)

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Actions",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                    )
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text("Refresh")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val code = "SJ" + String.format(
                                        Locale.US,
                                        "%08d",
                                        Random().nextInt(100_000_000),
                                    )
                                    LedgerRuntime.enqueue(
                                        context,
                                        sampleMessage(code),
                                        System.currentTimeMillis() / 1000,
                                    )
                                }
                                onRefresh()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Send a test message")
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Notification access settings")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { LedgerRuntime.stop() }
                                onRefresh()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Stop the background service")
                    }
                }
            }

            Text(
                text = "The service restarts automatically the next time a message arrives.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatsCard(stats: LedgerStats?) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (stats == null) "Service starting…" else "Service running",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
            )
            if (stats != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric("Ledger rows", stats.ledgerRows.toString())
                    Metric("Queued", stats.queued.toString())
                    Metric("Failed", stats.failed.toString())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric("Unparsed", stats.unparsed.toString())
                    Metric("Today spent", "Ksh${money(stats.todaySpendCents)}")
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A realistic confirmation dated today, used only by the test button. */
private fun sampleMessage(code: String): String {
    val now = java.util.Calendar.getInstance()
    val date = "${now.get(java.util.Calendar.DAY_OF_MONTH)}/" +
        "${now.get(java.util.Calendar.MONTH) + 1}/" +
        "${now.get(java.util.Calendar.YEAR) % 100}"
    val hour12 = ((now.get(java.util.Calendar.HOUR) + 11) % 12) + 1
    val minute = String.format(Locale.US, "%02d", now.get(java.util.Calendar.MINUTE))
    val meridiem = if (now.get(java.util.Calendar.AM_PM) == java.util.Calendar.PM) "PM" else "AM"
    return "$code Confirmed. Ksh350.00 paid to JAVA HOUSE. on $date at $hour12:$minute $meridiem. " +
        "New M-PESA balance is Ksh684.56. Transaction cost, Ksh0.00."
}
