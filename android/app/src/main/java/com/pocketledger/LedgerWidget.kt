package com.pocketledger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject

/**
 * Home screen widget: today's spend and how many jobs are still waiting.
 *
 * The listener and the debug screen call [refresh] when a job completes, so the
 * widget updates without the app being opened.
 */
class LedgerWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        refresh(context)
    }

    companion object {
        fun refresh(context: Context) {
            Thread {
                val json = runCatching { LedgerRuntime.stats(context) }
                    .getOrElse { LedgerRuntime.errorJson(it.message ?: "stats failed") }
                val stats = runCatching { JSONObject(json) }.getOrNull()

                val spend = stats?.optLong("today_spend_cents", 0L) ?: 0L
                val queued = stats?.optInt("queued_jobs", 0) ?: 0
                val failed = stats?.optInt("failed_jobs", 0) ?: 0

                val views = RemoteViews(context.packageName, R.layout.ledger_widget).apply {
                    setTextViewText(
                        R.id.widget_today,
                        if (stats == null) getStringOr(context, R.string.widget_offline)
                        else context.getString(R.string.widget_today, MpesaText.money(spend))
                    )
                    setTextViewText(
                        R.id.widget_queue,
                        when {
                            stats == null -> ""
                            queued == 0 && failed == 0 -> context.getString(R.string.widget_idle)
                            failed > 0 -> context.getString(R.string.widget_queue_failed, queued, failed)
                            else -> context.getString(R.string.widget_queue, queued)
                        }
                    )
                    setOnClickPendingIntent(R.id.widget_root, openApp(context))
                }

                AppWidgetManager.getInstance(context).updateAppWidget(
                    ComponentName(context, LedgerWidget::class.java),
                    views
                )
            }.start()
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun getStringOr(context: Context, id: Int): String = context.getString(id)
    }
}
