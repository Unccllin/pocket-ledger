package com.pocketledger

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * The real feature: M-Pesa confirmations become ledger rows.
 *
 * Android may start this service while the app process is dead, so the first
 * enqueue starts the Rivium service on demand. All work that touches the
 * database happens off the main thread.
 */
class MpesaListener : NotificationListenerService() {
    private val TAG = "pocketledger"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "listener connected")
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        val extras = notification.notification?.extras ?: return
        val parts = listOf(
            extras.getCharSequence(android.app.Notification.EXTRA_TITLE),
            extras.getCharSequence(android.app.Notification.EXTRA_TEXT),
            extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
        ).mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        if (parts.isEmpty()) {
            return
        }

        val candidate = parts.joinToString(" ")
        Log.i(TAG, "notification from ${notification.packageName}: ${candidate.take(120)}")
        if (!MpesaText.looksLike(candidate)) {
            return
        }

        val context = applicationContext
        Thread {
            val response = runCatching {
                LedgerRuntime.enqueue(context, candidate, System.currentTimeMillis() / 1000)
            }.getOrElse { LedgerRuntime.errorJson(it.message ?: "enqueue failed") }

            Log.i(TAG, "enqueue response: $response")
            val code = runCatching {
                org.json.JSONObject(response).optString("code", "")
            }.getOrDefault("")

            if (code.isNotEmpty()) {
                MpesaText.announceResult(context, code)
            } else {
                // Filtered (not M-Pesa after all) or an error: still refresh the
                // widget so the queue count stays honest.
                LedgerWidget.refresh(context)
            }
        }.start()
    }
}
