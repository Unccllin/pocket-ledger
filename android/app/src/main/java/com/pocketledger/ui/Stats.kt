package com.pocketledger.ui

import android.content.Context
import android.provider.Settings
import com.pocketledger.LedgerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One ledger row, as the native `stats` JSON describes it. */
data class LedgerRowModel(
    val code: String,
    val kind: String,
    val category: String,
    val amountCents: Long,
    val counterparty: String,
    val occurredAt: Long,
) {
    val isReceived: Boolean get() = kind == "received"
}

/** Everything the home screen and the widget need in one immutable snapshot. */
data class LedgerStats(
    val ledgerRows: Int,
    val todaySpendCents: Long,
    val todayIncomeCents: Long,
    val queued: Int,
    val running: Int,
    val failed: Int,
    val unparsed: Int,
    val latest: List<LedgerRowModel>,
) {
    val hasWork: Boolean get() = queued > 0 || running > 0
    val needsAttention: Boolean get() = failed > 0 || unparsed > 0
}

/** Parses the `stats` JSON; `null` means the service is not ready yet. */
fun parseStats(json: String): LedgerStats? = runCatching {
    val root = JSONObject(json)
    if (root.has("error")) return@runCatching null
    val array = root.optJSONArray("latest") ?: JSONArray()
    val rows = ArrayList<LedgerRowModel>(array.length())
    for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        rows += LedgerRowModel(
            code = row.optString("code"),
            kind = row.optString("kind"),
            category = row.optString("category"),
            amountCents = row.optLong("amount_cents"),
            counterparty = row.optString("counterparty"),
            occurredAt = row.optLong("occurred_at"),
        )
    }
    LedgerStats(
        ledgerRows = root.optInt("ledger_rows"),
        todaySpendCents = root.optLong("today_spend_cents"),
        todayIncomeCents = root.optLong("today_income_cents"),
        queued = root.optInt("queued_jobs"),
        running = root.optInt("running_jobs"),
        failed = root.optInt("failed_jobs"),
        unparsed = root.optInt("unparsed"),
        latest = rows,
    )
}.getOrNull()

/** Reads the latest snapshot off the UI thread. */
suspend fun loadStats(context: Context): LedgerStats? = withContext(Dispatchers.IO) {
    parseStats(LedgerRuntime.stats(context))
}

/** True when the user has granted Notification Access to this app. */
fun notificationAccessGranted(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: return false
    return enabled.split(':').any { it.startsWith(context.packageName) }
}
