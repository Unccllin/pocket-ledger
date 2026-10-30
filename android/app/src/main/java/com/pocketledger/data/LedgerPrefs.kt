package com.pocketledger.data

import android.content.Context

/** Small user preferences that belong to the UI, not the ledger. */
object LedgerPrefs {
    private const val PREFS = "ledger_prefs"
    private const val KEY_DAILY_LIMIT = "daily_limit_cents"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    /** Ksh 4,000: the canvas's example, used until the person changes it. */
    const val DEFAULT_DAILY_LIMIT_CENTS = 400_000L

    fun dailyLimitCents(context: Context): Long =
        prefs(context).getLong(KEY_DAILY_LIMIT, DEFAULT_DAILY_LIMIT_CENTS)

    fun setDailyLimitCents(context: Context, cents: Long) {
        prefs(context).edit().putLong(KEY_DAILY_LIMIT, cents.coerceAtLeast(0L)).apply()
    }

    fun onboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
