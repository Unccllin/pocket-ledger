package com.pocketledger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pocketledger.ui.money
import com.pocketledger.ui.parseStats

/**
 * Home screen widget: today's spend and how many jobs are still waiting.
 *
 * The listener and the diagnostics screen call [refresh] when a job completes,
 * so the widget updates without the app being opened.
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
                val stats = runCatching { parseStats(LedgerRuntime.stats(context)) }.getOrNull()

                val views = RemoteViews(context.packageName, R.layout.ledger_widget).apply {
                    setTextViewText(
                        R.id.widget_today,
                        "Ksh${money(stats?.todaySpendCents ?: 0L)}",
                    )
                    setTextViewText(
                        R.id.widget_queue,
                        when {
                            stats == null -> context.getString(R.string.widget_starting)
                            stats.queued == 0 && stats.failed == 0 ->
                                context.getString(R.string.widget_idle)
                            stats.failed > 0 ->
                                context.getString(
                                    R.string.widget_queue_failed,
                                    stats.queued,
                                    stats.failed,
                                )
                            else -> context.getString(R.string.widget_queue, stats.queued)
                        },
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
    }
}
