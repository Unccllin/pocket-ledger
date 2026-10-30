package com.pocketledger.data

import android.content.Context
import com.pocketledger.LedgerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The real repository: everything comes from the Rust worker through the JNI
 * `stats` call. The last successful answer is cached so widgets can draw
 * without waking the service.
 */
class JniLedgerRepository(context: Context) : LedgerRepository {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun snapshot(): LedgerState? = withContext(Dispatchers.IO) {
        val json = runCatching { LedgerRuntime.stats(appContext) }.getOrNull() ?: return@withContext null
        val state = parseState(json) ?: return@withContext null
        prefs.edit().putString(KEY_STATS, json).apply()
        state
    }

    override fun cached(): LedgerState? {
        val json = prefs.getString(KEY_STATS, null) ?: return null
        return parseState(json)
    }

    override suspend fun retryReview(id: Long): Boolean = withContext(Dispatchers.IO) {
        retry(appContext, id)
    }

    override suspend fun dismissReview(id: Long): Boolean = withContext(Dispatchers.IO) {
        dismiss(appContext, id)
    }

    companion object {
        private const val PREFS = "ledger_cache"
        private const val KEY_STATS = "stats_json"

        /** The widget's only read path: cache, never the service. */
        fun cachedState(context: Context): LedgerState? {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getString(KEY_STATS, null)?.let(::parseState)
        }

        /** Retry one parked message; true when it became a job again. */
        fun retry(context: Context, id: Long): Boolean {
            val json = LedgerRuntime.retryReview(context, id) ?: return false
            return runCatching {
                JSONObject(json).optString("outcome") == "enqueued"
            }.getOrDefault(false)
        }

        /** Ignore one parked message. */
        fun dismiss(context: Context, id: Long): Boolean {
            val json = LedgerRuntime.dismissReview(context, id) ?: return false
            return runCatching { JSONObject(json).optBoolean("ignored") }.getOrDefault(false)
        }

        /** Parses the worker's `stats` JSON; null for an error document. */
        fun parseState(json: String): LedgerState? = runCatching {
            val root = JSONObject(json)
            if (root.has("error")) return@runCatching null

            val latest = root.optJSONArray("latest").toRows()
            val review = root.optJSONArray("review").toReview()
            LedgerState(
                ledgerRows = root.optInt("ledger_rows"),
                spentTodayCents = root.optLong("today_spend_cents"),
                receivedTodayCents = root.optLong("today_income_cents"),
                paymentCount = root.optInt("today_payment_count"),
                balanceCents = if (root.isNull("balance_cents")) null else root.optLong("balance_cents"),
                topCategories = root.optJSONArray("top_categories").toCategories(),
                weekSpendCents = root.optLong("week_spend_cents"),
                prevWeekSpendCents = root.optLong("prev_week_spend_cents"),
                queued = root.optInt("queued_jobs"),
                running = root.optInt("running_jobs"),
                failed = root.optInt("failed_jobs"),
                unparsed = root.optInt("unparsed"),
                latest = latest,
                review = review,
            )
        }.getOrNull()

        private fun JSONArray?.toRows(): List<LedgerRow> {
            val array = this ?: return emptyList()
            val rows = ArrayList<LedgerRow>(array.length())
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                rows += LedgerRow(
                    code = row.optString("code"),
                    kind = row.optString("kind"),
                    category = row.optString("category"),
                    amountCents = row.optLong("amount_cents"),
                    counterparty = row.optString("counterparty"),
                    occurredAt = row.optLong("occurred_at"),
                    balanceCents = if (row.isNull("balance_cents")) null else row.optLong("balance_cents"),
                    rawText = if (row.isNull("raw_text")) null else row.optString("raw_text"),
                )
            }
            return rows
        }

        private fun JSONArray?.toCategories(): List<CategoryTotal> {
            val array = this ?: return emptyList()
            val totals = ArrayList<CategoryTotal>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                totals += CategoryTotal(
                    category = item.optString("category"),
                    amountCents = item.optLong("amount_cents"),
                )
            }
            return totals
        }

        private fun JSONArray?.toReview(): List<ReviewItem> {
            val array = this ?: return emptyList()
            val items = ArrayList<ReviewItem>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items += ReviewItem(
                    id = item.optLong("id"),
                    rawText = item.optString("raw_text"),
                    attempts = item.optInt("attempts"),
                    firstSeen = item.optLong("first_seen"),
                )
            }
            return items
        }
    }
}
