package com.pocketledger

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar
import java.util.Random

/**
 * Debug and status screen: the listener is the real feature, this screen makes
 * the pipeline observable and grants Notification Access.
 */
class MainActivity : Activity() {
    private val serviceStatus: TextView get() = findViewById(R.id.service_status)
    private val statsView: TextView get() = findViewById(R.id.stats)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.grant_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.debug_enqueue).setOnClickListener { enqueueSample() }
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.stop_service).setOnClickListener {
            Thread {
                val failure = LedgerRuntime.stop()
                runOnUiThread {
                    serviceStatus.text = failure ?: getString(R.string.service_stopped)
                }
            }.start()
        }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun refresh() {
        serviceStatus.text = getString(R.string.status_starting)
        Thread {
            val status = runCatching {
                val failure = LedgerRuntime.ensureStarted(this)
                if (failure == null) {
                    "Rivium service running (${LedgerRuntime.version()})"
                } else {
                    failure
                }
            }.getOrElse { "native library unavailable: ${it.message}" }
            val stats = runCatching { LedgerRuntime.stats(this) }
                .getOrElse { LedgerRuntime.errorJson(it.message ?: "stats failed") }
            val rendered = runCatching { JSONObject(stats).toString(2) }.getOrDefault(stats)
            runOnUiThread {
                serviceStatus.text = status
                statsView.text = rendered
            }
        }.start()
    }

    /** Debug aid only: a real incoming notification is the actual trigger. */
    private fun enqueueSample() {
        Thread {
            runCatching {
                LedgerRuntime.enqueue(this, sampleMessage(), System.currentTimeMillis() / 1000)
            }
            refresh()
        }.start()
    }

    /**
     * Builds a Ksh350 JAVA HOUSE message dated today (so "today's spend" moves)
     * with a fresh transaction code (so idempotency does not swallow it).
     */
    private fun sampleMessage(): String {
        val now = Calendar.getInstance()
        val date = "${now.get(Calendar.DAY_OF_MONTH)}/${now.get(Calendar.MONTH) + 1}/${now.get(Calendar.YEAR) % 100}"
        val hour12 = ((now.get(Calendar.HOUR) + 11) % 12) + 1
        val minute = String.format("%02d", now.get(Calendar.MINUTE))
        val meridiem = if (now.get(Calendar.AM_PM) == Calendar.PM) "PM" else "AM"
        val code = "SJA" + String.format("%07d", Random().nextInt(10_000_000))
        return "$code Confirmed. Ksh350.00 paid to JAVA HOUSE. on $date at $hour12:$minute $meridiem. " +
            "New M-PESA balance is Ksh684.56. Transaction cost, Ksh0.00."
    }
}
