package com.pocketledger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pocketledger.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketledger.ui.design.Forest
import com.pocketledger.ui.design.Ink

/** Bottom navigation: words, not icons. */
enum class LedgerTab(val labelRes: Int) {
    Home(R.string.tab_home),
    Activity(R.string.tab_activity),
    Settings(R.string.tab_settings),
}

@Composable
fun BottomNav(selected: LedgerTab, onSelect: (LedgerTab) -> Unit) {
    Surface(color = Ink.surface, shadowElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) Forest.forest else Ink.surface,
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelect(tab) },
                        ),
                ) {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        ),
                        color = if (isSelected) Ink.surface else Ink.muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Activity filters. Selected chip is forest with white text, as specced. */
enum class ActivityFilter(val labelRes: Int) {
    All(R.string.filter_all),
    Spent(R.string.filter_spent),
    Received(R.string.filter_received),
    Review(R.string.filter_review),
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) Forest.forest else Ink.surface,
        border = if (selected) null else BorderStroke(1.dp, Ink.line),
        modifier = Modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            ),
            color = if (selected) Ink.surface else Ink.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}
