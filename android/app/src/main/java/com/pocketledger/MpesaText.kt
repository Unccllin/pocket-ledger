package com.pocketledger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.pocketledger.ui.LedgerRowModel
import com.pocketledger.ui.categoryLabel
import com.pocketledger.ui.money
import com.pocketledger.ui.parseStats

/**
 * M-Pesa-shaped text handling and the result notification.
 *
 * Kotlin only does the cheap shape check so unrelated notifications never wake
 * the native library; the Rust parser remains the authority on what a message
 * actually means.
 */
object MpesaText {
    private val CODE = Regex("\\b[A-Z]{2}[A-Z0-9]{8}\\b")
    private const val CHANNEL_ID = "ledger-results"

    /**
     * Mirrors the Rust shape rule: ten characters, two leading letters, and at
     * least one digit (so the word `WITHDRAWAL` is not mistaken for a code).
     * Ambiguity here is harmless anyway: Rust is the authority.
     */
    fun looksLike(text: String): Boolean =
        text.contains("confirmed", ignoreCase = true) && codeOf(text) != null

    fun codeOf(text: String): String? =
        CODE.findAll(text)
            .map { it.value }
            .firstOrNull { code -> code.any(Char::isDigit) }

    /**
     * Waits (briefly) for the queued code to reach the ledger, then tells the
     * user and refreshes the widget. Runs on a background thread.
     */
    fun announceResult(context: Context, code: String) {
        val row = waitForRow(context, code)
        LedgerWidget.refresh(context)
        val match = row ?: return

        val counterparty = match.counterparty
        val category = categoryLabel(match.category)
        val cents = match.amountCents

        val title = if (match.isReceived) {
            "Received Ksh${money(cents)}"
        } else {
            "Spent Ksh${money(cents)}"
        }
        post(context, title, "$counterparty \u00B7 $category")
    }

    private fun waitForRow(context: Context, code: String): LedgerRowModel? {
        repeat(20) {
            val stats = runCatching { parseStats(LedgerRuntime.stats(context)) }.getOrNull()
            stats?.latest?.firstOrNull { it.code == code }?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun post(context: Context, title: String, detail: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Transaction results",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(detail)
            .setAutoCancel(true)
            .build()
        manager.notify(title.hashCode(), notification)
    }
}
