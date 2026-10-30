package com.pocketledger

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Starts the Rivium service inside the app process and calls the native bridge.
 *
 * The listener can be woken by Android while the app process is dead, so every
 * entry point calls [ensureStarted] first: it starts the Rust service on demand
 * and is a cheap status check once running.
 */
object LedgerRuntime {
    const val STATUS_IDLE = 0
    const val STATUS_RUNNING = 2

    private const val TAG = "pocketledger"

    @Synchronized
    fun ensureStarted(context: Context): String? {
        return try {
            Log.i(TAG, "ensureStarted: querying native status")
            val status = Bridge.nativeStatus()
            Log.i(TAG, "ensureStarted: status=$status")
            if (status == STATUS_RUNNING) {
                null
            } else {
                val root = File(context.applicationContext.filesDir, "ledger")
                if (!root.exists() && !root.mkdirs()) {
                    return "cannot create ${root.absolutePath}"
                }
                Log.i(TAG, "ensureStarted: nativeStart root=${root.absolutePath}")
                val code = Bridge.nativeStart(arrayOf("--root", root.absolutePath))
                Log.i(TAG, "ensureStarted: nativeStart returned $code")
                // 0 = started, 1 = already running.
                if (code == 0 || code == 1) null else "nativeStart=$code ${Bridge.nativeLastError()}"
            }
        } catch (error: Throwable) {
            "native library unavailable: ${error.message}"
        }
    }

    fun stop(): String? {
        return try {
            val code = Bridge.nativeStop(2000)
            if (code == 0 || code == 2) null else "nativeStop=$code ${Bridge.nativeLastError()}"
        } catch (error: Throwable) {
            "native library unavailable: ${error.message}"
        }
    }

    fun version(): String = try {
        Bridge.nativeVersion()
    } catch (error: Throwable) {
        "unavailable"
    }

    fun enqueue(context: Context, rawText: String, receivedAtSeconds: Long): String {
        ensureStarted(context)?.let { return errorJson(it) }
        return try {
            Log.i(TAG, "enqueue: calling nativeEnqueue")
            val response = Bridge.nativeEnqueue(rawText, receivedAtSeconds)
            Log.i(TAG, "enqueue: nativeEnqueue returned")
            response
        } catch (error: Throwable) {
            errorJson("native library unavailable: ${error.message}")
        }
    }

    fun stats(context: Context): String {
        ensureStarted(context)?.let { return errorJson(it) }
        return try {
            Bridge.nativeStats()
        } catch (error: Throwable) {
            errorJson("native library unavailable: ${error.message}")
        }
    }

    /** Retry one parked message; returns the native JSON, or null on failure. */
    fun retryReview(context: Context, id: Long): String? {
        ensureStarted(context)?.let { return null }
        return try {
            Bridge.nativeRetry(id)
        } catch (error: Throwable) {
            Log.w(TAG, "nativeRetry failed: ${error.message}")
            null
        }
    }

    /** Ignore one parked message; returns the native JSON, or null on failure. */
    fun dismissReview(context: Context, id: Long): String? {
        ensureStarted(context)?.let { return null }
        return try {
            Bridge.nativeDismiss(id)
        } catch (error: Throwable) {
            Log.w(TAG, "nativeDismiss failed: ${error.message}")
            null
        }
    }

    fun errorJson(message: String): String =
        """{"error":${org.json.JSONObject.quote(message)}}"""
}
