package com.pocketledger.ui.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.pocketledger.data.FakeLedgerRepository
import com.pocketledger.data.LedgerState
import com.pocketledger.ui.components.ActivityFilter
import com.pocketledger.ui.components.LedgerRowItem
import com.pocketledger.ui.components.ReviewRow
import com.pocketledger.ui.components.WaitingRow
import com.pocketledger.ui.design.Ink
import com.pocketledger.ui.design.PocketLedgerTheme
import com.pocketledger.ui.design.Tints
import com.pocketledger.ui.screens.ActivityScreen
import com.pocketledger.ui.screens.FirstRunScreen
import com.pocketledger.ui.screens.HomeScreen
import com.pocketledger.ui.screens.SettingsScreen

/**
 * Static previews for design review: every screen and every row state, at the
 * canvas's 390dp width. They use [FakeLedgerRepository], so they render without
 * the worker.
 */
private const val LIMIT = 400_000L

@Composable
private fun Wrap(content: @Composable () -> Unit) {
    PocketLedgerTheme { content() }
}

@Preview(name = "Home", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun HomePreview() = Wrap {
    HomeScreen(
        state = FakeLedgerRepository.sampleState(),
        dailyLimitCents = LIMIT,
        accessGranted = true,
        onOpenRow = {},
        onOpenActivity = {},
        onOpenSettings = {},
        onGrantAccess = {},
    )
}

@Preview(name = "Home empty", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun HomeEmptyPreview() = Wrap {
    HomeScreen(
        state = LedgerState(),
        dailyLimitCents = LIMIT,
        accessGranted = true,
        onOpenRow = {},
        onOpenActivity = {},
        onOpenSettings = {},
        onGrantAccess = {},
    )
}

@Preview(name = "Home waiting + no access", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun HomeWaitingPreview() = Wrap {
    HomeScreen(
        state = FakeLedgerRepository.sampleState().copy(queued = 2, unparsed = 1),
        dailyLimitCents = LIMIT,
        accessGranted = false,
        onOpenRow = {},
        onOpenActivity = {},
        onOpenSettings = {},
        onGrantAccess = {},
    )
}

@Preview(name = "Activity", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ActivityPreview() = Wrap {
    ActivityScreen(
        state = FakeLedgerRepository.reviewState(),
        filter = ActivityFilter.All,
        onFilter = {},
        onOpenRow = {},
        onOpenReview = {},
    )
}

@Preview(name = "Rows: done, waiting, review", widthDp = 390, showBackground = true)
@Composable
private fun RowStatesPreview() = Wrap {
    LedgerRowItem(
        title = "Java House",
        subtitle = "Food · 1:00 PM",
        avatarText = "JH",
        avatarBackground = Tints.mintTint,
        avatarContent = Ink.ink,
        amountText = "Ksh 350",
        amountColor = Ink.ink,
        semanticLabel = "Java House, Food, minus 350 shillings, 1:00 PM",
        onClick = {},
    )
    WaitingRow()
    ReviewRow(count = 1, onClick = {})
}

@Preview(name = "Settings", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun SettingsPreview() = Wrap {
    SettingsScreen(
        accessGranted = true,
        dailyLimitCents = LIMIT,
        version = "pocket-ledger 0.3.0",
        onDailyLimitChange = {},
        onGrantAccess = {},
        onStopService = {},
    )
}

@Preview(name = "First run", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun FirstRunPreview() = Wrap {
    FirstRunScreen(accessGranted = false, onOpenSettings = {}, onContinue = {})
}
