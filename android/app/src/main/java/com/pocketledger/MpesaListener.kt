package com.pocketledger

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pocketledger.widget.Widgets

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
        Log.i(TAG, "listener created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected")
        catchUpOnActiveNotifications()
    }

    /**
     * Android sometimes drops the binding and does not restore it by itself
     * (observed after the app sat idle for days). Ask for it back; the rebind is
     * throttled by the system, so a short delay avoids being ignored.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "listener disconnected; requesting rebind")
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                requestRebind(ComponentName(this, MpesaListener::class.java))
            }.onFailure { Log.w(TAG, "rebind request failed: ${it.message}") }
        }, 1_000)
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        handle(notification, announce = true)
    }

    /**
     * Confirmations that arrived while the binding was down are still in the
     * shade. The transaction code is the idempotency key, so replaying them on
     * every reconnect is safe: each code produces at most one ledger row, and no
     * result notification is posted for a recovery.
     */
    private fun catchUpOnActiveNotifications() {
        val context = applicationContext
        Thread {
            val active = runCatching { activeNotifications }.getOrNull() ?: return@Thread
            var enqueued = 0
            for (notification in active) {
                val candidate = candidateText(notification) ?: continue
                if (!MpesaText.looksLike(candidate)) continue
                val response = runCatching {
                    LedgerRuntime.enqueue(context, candidate, System.currentTimeMillis() / 1000)
                }.getOrElse { LedgerRuntime.errorJson(it.message ?: "catch-up enqueue failed") }
                Log.i(TAG, "catch-up enqueue: $response")
                val outcome = runCatching {
                    org.json.JSONObject(response).optString("outcome")
                }.getOrDefault("")
                if (outcome == "enqueued") enqueued++
            }
            Log.i(TAG, "catch-up scanned ${active.size} active, recovered $enqueued")
            if (enqueued > 0) Widgets.refresh(context)
        }.start()
    }

    private fun handle(notification: StatusBarNotification, announce: Boolean) {
        val candidate = candidateText(notification) ?: return
        Log.i(TAG, "notification from ${notification.packageName}: ${candidate.take(160)}")
        if (!MpesaText.looksLike(candidate)) {
            Log.i(TAG, "ignored: no M-Pesa code + Confirmed in that text")
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

            if (code.isNotEmpty() && announce) {
                MpesaText.announceResult(context, code)
            } else {
                // Filtered, a recovery, or an error: still refresh the widget so
                // the queue and today's total stay honest.
                Widgets.refresh(context)
            }
        }.start()
    }

    /** The text a notification carries, wherever this app hid it. */
    private fun candidateText(notification: StatusBarNotification): String? {
        val extras = notification.notification?.extras ?: return null
        val parts = buildList {
            add(extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
            add(extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
            add(extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString())
            // Inbox-style and grouped notifications keep the body elsewhere.
            extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
                ?.forEach { add(it?.toString()) }
            // Google Messages puts one Bundle per message under android.messages.
            extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)
                ?.forEach { message ->
                    if (message is Bundle) add(message.getCharSequence("text")?.toString())
                }
        }.mapNotNull { it?.takeIf(String::isNotBlank) }
        if (parts.isEmpty()) return null
        return parts.joinToString(" ")
    }
}
