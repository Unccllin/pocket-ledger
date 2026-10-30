package com.pocketledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.pocketledger.MainActivity
import com.pocketledger.R
import com.pocketledger.data.JniLedgerRepository
import com.pocketledger.data.LedgerState
import com.pocketledger.ui.format.ksh

/**
 * Widget A: today's spend on forest, with the queued count in amber only when
 * something is waiting.
 */
class SpentTodayWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) = Widgets.refresh(context)
}

/** Widget B: this week against the week before, on surface. */
class WeekWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) = Widgets.refresh(context)
}

/**
 * Widget rendering.
 *
 * Reads the last-known snapshot from the on-device cache, never from the Rust
 * service: a widget must draw instantly, with zero data, and without starting
 * anything. [refresh] is called when a message finishes (from the listener),
 * not on a timer.
 */
object Widgets {
    fun refresh(context: Context) {
        Thread {
            val state = JniLedgerRepository.cachedState(context)
            val manager = AppWidgetManager.getInstance(context)
            manager.updateAppWidget(
                ComponentName(context, SpentTodayWidget::class.java),
                spentViews(context, state),
            )
            manager.updateAppWidget(
                ComponentName(context, WeekWidget::class.java),
                weekViews(context, state),
            )
        }.start()
    }

    private fun spentViews(context: Context, state: LedgerState?): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_spent_today).apply {
            setTextViewText(
                R.id.widget_spent_amount,
                state?.let { ksh(it.spentTodayCents) } ?: "Ksh \u2014",
            )
            val waiting = state?.waiting ?: 0
            if (waiting > 0) {
                setViewVisibility(R.id.widget_spent_waiting, android.view.View.VISIBLE)
                setTextViewText(
                    R.id.widget_spent_waiting,
                    if (waiting == 1) {
                        context.getString(R.string.widget_waiting_one)
                    } else {
                        context.getString(R.string.widget_waiting_many, waiting)
                    },
                )
            } else {
                setViewVisibility(R.id.widget_spent_waiting, android.view.View.GONE)
            }
            setOnClickPendingIntent(R.id.widget_spent_root, openApp(context))
        }

    private fun weekViews(context: Context, state: LedgerState?): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_week).apply {
            setTextViewText(
                R.id.widget_week_amount,
                state?.let { ksh(it.weekSpendCents) } ?: "Ksh \u2014",
            )
            val change = state?.let { it.weekChangePercent to it.weekIsLower }
            val (text, color) = when {
                state == null -> context.getString(R.string.widget_no_data_yet) to R.color.muted
                state.prevWeekSpendCents <= 0L ->
                    context.getString(R.string.widget_no_comparison) to R.color.muted
                change?.first == null ->
                    context.getString(R.string.widget_same_as_last_week) to R.color.ink
                change.second ->
                    context.getString(R.string.widget_less_than_last_week, change.first) to R.color.green
                // More is ink, never red: the app does not shame spending.
                else ->
                    context.getString(R.string.widget_more_than_last_week, change.first) to R.color.ink
            }
            setTextViewText(R.id.widget_week_change, text)
            setTextColor(R.id.widget_week_change, ContextCompat.getColor(context, color))
            setOnClickPendingIntent(R.id.widget_week_root, openApp(context))
        }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
