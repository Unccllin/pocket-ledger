package com.pocketledger.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pocketledger.LedgerRuntime
import com.pocketledger.data.JniLedgerRepository
import com.pocketledger.data.LedgerPrefs
import com.pocketledger.data.LedgerRepository
import com.pocketledger.data.LedgerRow
import com.pocketledger.data.LedgerState
import com.pocketledger.data.ReviewItem
import com.pocketledger.data.notificationAccessGranted
import com.pocketledger.data.openNotificationAccessSettings
import com.pocketledger.ui.components.ActivityFilter
import com.pocketledger.ui.components.BottomNav
import com.pocketledger.ui.components.LedgerTab
import com.pocketledger.ui.screens.ActivityScreen
import com.pocketledger.ui.screens.DetailsSheet
import com.pocketledger.ui.screens.FirstRunScreen
import com.pocketledger.ui.screens.HomeScreen
import com.pocketledger.ui.screens.ReviewSheet
import com.pocketledger.ui.screens.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app shell: first run, three tabs, and the two sheets.
 *
 * It talks only to [LedgerRepository]; previews and pre-Phase-5 work use the
 * fake implementation, the phone uses [JniLedgerRepository].
 */
@Composable
fun LedgerApp(repository: LedgerRepository = rememberDefaultRepository()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(LedgerTab.Home) }
    var state by remember { mutableStateOf(repository.cached() ?: LedgerState()) }
    var accessGranted by remember { mutableStateOf(notificationAccessGranted(context)) }
    var onboardingDone by remember { mutableStateOf(LedgerPrefs.onboardingDone(context)) }
    var dailyLimitCents by remember { mutableStateOf(LedgerPrefs.dailyLimitCents(context)) }
    var filter by remember { mutableStateOf(ActivityFilter.All) }
    var detail by remember { mutableStateOf<LedgerRow?>(null) }
    var review by remember { mutableStateOf<ReviewItem?>(null) }

    // Poll only while a screen is visible; Activity refreshes faster because
    // rows change state there (waiting -> done).
    LaunchedEffect(tab, onboardingDone) {
        while (true) {
            // Access can change while the explainer is on screen (the person
            // just came back from the system setting), so always poll it.
            accessGranted = notificationAccessGranted(context)
            // Never wake the Rust service before the explainer is done.
            if (onboardingDone) {
                repository.snapshot()?.let { state = it }
            }
            delay(if (tab == LedgerTab.Activity) 1_000L else 2_500L)
        }
    }

    fun refresh() {
        scope.launch {
            repository.snapshot()?.let { state = it }
            accessGranted = notificationAccessGranted(context)
        }
    }

    // Show the explainer until the person has seen it through. Keeping it up
    // after access is granted gives the specced "granted" state and a Continue.
    if (!onboardingDone) {
        FirstRunScreen(
            accessGranted = accessGranted,
            onOpenSettings = { openNotificationAccessSettings(context) },
            onContinue = {
                LedgerPrefs.setOnboardingDone(context)
                onboardingDone = true
            },
        )
        return
    }

    Scaffold(
        containerColor = com.pocketledger.ui.design.Ink.ground,
        bottomBar = { BottomNav(selected = tab, onSelect = { tab = it }) },
    ) { insets ->
        val content = Modifier.padding(insets)
        when (tab) {
            LedgerTab.Home -> HomeScreen(
                state = state,
                dailyLimitCents = dailyLimitCents,
                accessGranted = accessGranted,
                onOpenRow = { detail = it },
                onOpenActivity = { tab = LedgerTab.Activity },
                onOpenSettings = { tab = LedgerTab.Settings },
                onGrantAccess = { openNotificationAccessSettings(context) },
                modifier = content,
            )

            LedgerTab.Activity -> ActivityScreen(
                state = state,
                filter = filter,
                onFilter = { filter = it },
                onOpenRow = { detail = it },
                onOpenReview = { review = state.review.firstOrNull() },
                modifier = content,
            )

            LedgerTab.Settings -> SettingsScreen(
                accessGranted = accessGranted,
                dailyLimitCents = dailyLimitCents,
                version = LedgerRuntime.version(),
                onDailyLimitChange = { cents ->
                    dailyLimitCents = cents
                    LedgerPrefs.setDailyLimitCents(context, cents)
                },
                onGrantAccess = { openNotificationAccessSettings(context) },
                onStopService = {
                    scope.launch {
                        withContext(Dispatchers.IO) { LedgerRuntime.stop() }
                        refresh()
                    }
                },
                modifier = content,
            )
        }
    }

    detail?.let { row ->
        DetailsSheet(row = row, onDismiss = { detail = null })
    }

    review?.let { item ->
        ReviewSheet(
            item = item,
            remaining = state.review.size,
            onRetry = {
                scope.launch {
                    val moved = repository.retryReview(item.id)
                    review = null
                    if (moved) refresh()
                }
            },
            onIgnore = {
                scope.launch {
                    repository.dismissReview(item.id)
                    review = null
                    refresh()
                }
            },
            onDismiss = { review = null },
        )
    }
}

@Composable
private fun rememberDefaultRepository(): LedgerRepository {
    val context = LocalContext.current.applicationContext
    return remember(context) { JniLedgerRepository(context) }
}
