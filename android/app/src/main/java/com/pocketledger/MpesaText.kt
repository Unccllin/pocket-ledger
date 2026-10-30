package com.pocketledger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * M-Pesa-shaped text handling and the result notification.
 *
 * Kotlin only does the cheap shape check so unrelated notifications never wake
 * the native library; the Rust parser remains the authority on what a message
 * actually means.
 */
object MpesaText {
    private val CODE = Regex("\\b[A-Z]{3}[A-Z0-9]{7}\\b")
    private const val CHANNEL_ID = "ledger-results"

    fun looksLike(text: String): Boolean =
        text.contains("confirmed", ignoreCase = true) && CODE.containsMatchIn(text)

    fun codeOf(text: String): String? = CODE.find(text)?.value

    /** Formats integer cents as `Ksh1,234.56` without floating point. */
    fun money(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = Math.abs(cents)
        val whole = abs / 100
        val fraction = (abs % 100).toString().padStart(2, '0')
        return String.format(Locale.US, "%sKsh%,d.%s", sign, whole, fraction)
    }

    /**
     * Waits (briefly) for the queued code to reach the ledger, then tells the
     * user and refreshes the widget. Runs on a background thread.
     */
    fun announceResult(context: Context, code: String) {
        val row = waitForRow(context, code)
        LedgerWidget.refresh(context)
        val entry = row ?: return
        val kind = entry.optString("kind")
        val name = entry.optString("counterparty")
        val cents = entry.optLong("amount_cents")
        val title = if (kind == "received") {
            "Received ${money(cents)} from $name"
        } else {
            "Spent ${money(cents)} at $name"
        }
        post(context, title, entry.optString("category"))
    }

    private fun waitForRow(context: Context, code: String): JSONObject? {
        repeat(20) {
            val stats = runCatching { JSONObject(LedgerRuntime.stats(context)) }.getOrNull()
            val latest = stats?.optJSONArray("latest")
            if (latest != null) {
                for (index in 0 until latest.length()) {
                    val row = latest.optJSONObject(index) ?: continue
                    if (row.optString("code") == code) return row
                }
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun post(context: Context, title: String, category: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ledger results", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText("Category: $category")
            .setAutoCancel(true)
            .build()
        manager.notify(title.hashCode(), notification)
    }
}
