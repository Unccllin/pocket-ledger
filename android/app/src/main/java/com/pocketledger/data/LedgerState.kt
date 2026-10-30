package com.pocketledger.data

/** One ledger row as the worker reports it. */
data class LedgerRow(
    val code: String,
    val kind: String,
    val category: String,
    val amountCents: Long,
    val counterparty: String,
    val occurredAt: Long,
    val balanceCents: Long?,
    /** The original message with phone numbers masked; null for old rows. */
    val rawText: String?,
) {
    val isReceived: Boolean get() = kind == "received"
}

/** One category's total for today. */
data class CategoryTotal(
    val category: String,
    val amountCents: Long,
)

/** A message parked for review. */
data class ReviewItem(
    val id: Long,
    val rawText: String,
    val attempts: Int,
    val firstSeen: Long,
)

/**
 * Everything the screens and widgets need, in one immutable snapshot.
 *
 * This is the UI's whole world: it never sees Apalis, SQLite or the JNI layer.
 */
data class LedgerState(
    val ledgerRows: Int = 0,
    val spentTodayCents: Long = 0L,
    val receivedTodayCents: Long = 0L,
    val paymentCount: Int = 0,
    val balanceCents: Long? = null,
    val topCategories: List<CategoryTotal> = emptyList(),
    val weekSpendCents: Long = 0L,
    val prevWeekSpendCents: Long = 0L,
    val queued: Int = 0,
    val running: Int = 0,
    val failed: Int = 0,
    val unparsed: Int = 0,
    val latest: List<LedgerRow> = emptyList(),
    val review: List<ReviewItem> = emptyList(),
) {
    /** Work that is normal, never an error: a calm "waiting" state. */
    val waiting: Int get() = queued + running

    val needsReview: Boolean get() = review.isNotEmpty()

    val hasData: Boolean get() = ledgerRows > 0

    /** Change against the previous seven days, when there is anything to compare. */
    val weekChangePercent: Int?
        get() {
            if (prevWeekSpendCents <= 0L || weekSpendCents == prevWeekSpendCents) return null
            val delta = (weekSpendCents - prevWeekSpendCents).toDouble() / prevWeekSpendCents
            val percent = kotlin.math.round(kotlin.math.abs(delta) * 100).toInt()
            return if (percent == 0) null else percent
        }

    val weekIsLower: Boolean get() = weekSpendCents < prevWeekSpendCents
}

/** The UI's only dependency on the worker. */
interface LedgerRepository {
    /** Reads live state from the worker; null when the service cannot answer. */
    suspend fun snapshot(): LedgerState?

    /** Last known state from the on-device cache. Never starts the service. */
    fun cached(): LedgerState?

    /** Re-queues one parked message. `true` when it became a job again. */
    suspend fun retryReview(id: Long): Boolean

    /** Marks one parked message as ignored. */
    suspend fun dismissReview(id: Long): Boolean
}
