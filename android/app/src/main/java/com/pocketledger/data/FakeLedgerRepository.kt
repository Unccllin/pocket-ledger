package com.pocketledger.data

/**
 * Sample data for previews and for building screens without the worker.
 *
 * Everything is synthetic and shaped like the worker's `stats` document, so the
 * swap to [JniLedgerRepository] changes no screen code.
 */
class FakeLedgerRepository(initial: LedgerState = sampleState()) : LedgerRepository {
    var state: LedgerState = initial
        private set

    override suspend fun snapshot(): LedgerState = state

    override fun cached(): LedgerState = state

    override suspend fun retryReview(id: Long): Boolean {
        val item = state.review.firstOrNull { it.id == id } ?: return false
        state = state.copy(
            review = state.review - item,
            queued = state.queued + 1,
        )
        return true
    }

    override suspend fun dismissReview(id: Long): Boolean {
        val item = state.review.firstOrNull { it.id == id } ?: return false
        state = state.copy(
            review = state.review - item,
            unparsed = (state.unparsed - 1).coerceAtLeast(0),
        )
        return true
    }

    companion object {
        private const val TODAY = 1_791_198_900L // 2026-10-05 14:15 EAT
        private const val DAY = 86_400L

        fun sampleState(): LedgerState = LedgerState(
            ledgerRows = 42,
            spentTodayCents = 245_000,
            receivedTodayCents = 150_000,
            paymentCount = 3,
            balanceCents = 1_234_000,
            topCategories = listOf(
                CategoryTotal("food", 95_000),
                CategoryTotal("utilities", 85_000),
                CategoryTotal("transport", 65_000),
            ),
            weekSpendCents = 1_820_000,
            prevWeekSpendCents = 1_980_000,
            queued = 2,
            running = 0,
            failed = 0,
            unparsed = 0,
            latest = listOf(
                row("SJ41H2K9LM", "buygoods", "food", 35_000, "Java House", TODAY + 300),
                row("SJ52K3M7NQ", "paybill", "utilities", 85_000, "KPLC Prepaid", TODAY - 3_000),
                row("SJ63L4P8RT", "received", "transfer", 150_000, "Jane Doe", TODAY - 8_000),
                row("SJ74M5Q9SV", "airtime", "airtime", 10_000, "Safaricom", TODAY - 84_000),
                row("SJ85N6R2TW", "buygoods", "transport", 65_000, "Uber Kenya", TODAY - DAY - 300),
                row("SJ96P7S3VX", "withdraw", "cash", 200_000, "Agent 123456", TODAY - DAY - 900),
            ),
            review = emptyList(),
        )

        fun reviewState(): LedgerState = sampleState().copy(
            unparsed = 1,
            review = listOf(
                ReviewItem(
                    id = 1,
                    rawText = "SJ9Z8Y7X6W Confirmed. Something entirely new happened on 5/10/26 at 2:15 PM.",
                    attempts = 3,
                    firstSeen = TODAY - 600,
                ),
            ),
        )

        fun emptyState(): LedgerState = LedgerState()

        private fun row(
            code: String,
            kind: String,
            category: String,
            amountCents: Long,
            counterparty: String,
            occurredAt: Long,
        ) = LedgerRow(
            code = code,
            kind = kind,
            category = category,
            amountCents = amountCents,
            counterparty = counterparty,
            occurredAt = occurredAt,
            balanceCents = 1_234_000,
            rawText = "$code Confirmed. Ksh${amountCents / 100}.00 " +
                "for $counterparty. New M-PESA balance is Ksh12,340.00.",
        )
    }
}
