package com.pocketledger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.pocketledger.data.JniLedgerRepository
import com.pocketledger.data.LedgerPrefs
import com.pocketledger.data.LedgerRow
import com.pocketledger.data.LedgerState
import com.pocketledger.ui.format.categoryLabel
import com.pocketledger.ui.format.displayName
import com.pocketledger.ui.format.ksh
import com.pocketledger.ui.format.limitPercent
import com.pocketledger.widget.Widgets
import kotlinx.coroutines.runBlocking

/**
 * M-Pesa-shaped text handling and the result notification.
 *
 * Kotlin only does the cheap shape check so unrelated notifications never wake
 * the native library; the Rust parser remains the authority on what a message
 * actually means.
 */
object MpesaText {
    private val CODE = Regex("\\b[A-Z]{2}[A-Z0-9]{8}\\b")
    private const val CHANNEL_ID = "transactions"
    private const val GROUP_KEY = "pocketledger.transactions"
    private const val SUMMARY_ID = 1_000_001

    /**
     * Mirrors the Rust shape rule: ten characters, two leading letters, and at
     * least one digit (so the word `WITHDRAWAL` is not mistaken for a code).
     */
    fun looksLike(text: String): Boolean =
        text.contains("confirmed", ignoreCase = true) && codeOf(text) != null

    fun codeOf(text: String): String? =
        CODE.findAll(text)
            .map { it.value }
            .firstOrNull { code -> code.any(Char::isDigit) }

    /**
     * Waits (briefly) for the queued code to reach the ledger, then tells the
     * user and refreshes the widgets. Runs on a background thread.
     */
    fun announceResult(context: Context, code: String) {
        val repository = JniLedgerRepository(context)
        val state = waitForRow(repository, code)
        Widgets.refresh(context)
        val row = state?.latest?.firstOrNull { it.code == code } ?: return

        val title = if (row.isReceived) {
            context.getString(
                R.string.notification_received,
                ksh(row.amountCents),
                displayName(row.counterparty),
            )
        } else {
            context.getString(
                R.string.notification_spent,
                ksh(row.amountCents),
                displayName(row.counterparty),
            )
        }
        post(context, title, bodyFor(context, row))
    }

    private fun waitForRow(repository: JniLedgerRepository, code: String): LedgerState? {
        repeat(20) {
            val state = runBlocking { repository.snapshot() }
            if (state?.latest?.any { it.code == code } == true) return state
            Thread.sleep(250)
        }
        return null
    }

    private fun bodyFor(context: Context, row: LedgerRow): String {
        val category = categoryLabel(row.category)
        if (row.isReceived) {
            val balance = row.balanceCents
            return if (balance == null) {
                category
            } else {
                context.getString(R.string.notification_balance_body, category, ksh(balance))
            }
        }
        val limit = LedgerPrefs.dailyLimitCents(context)
        val spent = JniLedgerRepository.cachedState(context)?.spentTodayCents ?: 0L
        return if (limit > 0) {
            context.getString(R.string.notification_limit_body, category, limitPercent(spent, limit))
        } else {
            val balance = row.balanceCents
            if (balance == null) {
                category
            } else {
                context.getString(R.string.notification_balance_body, category, ksh(balance))
            }
        }
    }

    private fun post(context: Context, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setGroup(GROUP_KEY)
            .setAutoCancel(true)
            .build()
        manager.notify(title.hashCode(), notification)
        updateSummary(context, manager)
    }

    /**
     * Several confirmations can land at once (a burst of M-Pesa traffic). They
     * share a group, and one summary says how many arrived instead of a pile of
     * separate lines.
     */
    private fun updateSummary(context: Context, manager: NotificationManager) {
        val count = manager.activeNotifications.count {
            it.notification.group == GROUP_KEY && it.id != SUMMARY_ID
        }
        if (count <= 1) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val summary = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_summary_title, count))
            .setContentText(context.getString(R.string.notification_summary_body))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}
