package com.pocketledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pocketledger.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketledger.ui.components.SectionLabel
import com.pocketledger.ui.design.Forest
import com.pocketledger.ui.design.Ink

/**
 * Settings: the few things a person can decide, plus the technical switches
 * that used to live on the old debug screen.
 */
@Composable
fun SettingsScreen(
    accessGranted: Boolean,
    dailyLimitCents: Long,
    version: String,
    onDailyLimitChange: (Long) -> Unit,
    onGrantAccess: () -> Unit,
    onStopService: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local text so the cursor never jumps; the preference follows every edit.
    var limitText by remember {
        mutableStateOf(if (dailyLimitCents <= 0) "" else (dailyLimitCents / 100).toString())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = Ink.ink,
        )
        Spacer(Modifier.height(20.dp))

        SectionLabel(stringResource(R.string.settings_daily_limit))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = limitText,
            onValueChange = { input ->
                val digits = input.filter(Char::isDigit).take(9)
                limitText = digits
                onDailyLimitChange(digits.toLongOrNull()?.times(100) ?: 0L)
            },
            label = { Text(stringResource(R.string.settings_limit_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_limit_help),
            style = MaterialTheme.typography.bodySmall,
            color = Ink.muted,
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.settings_notifications))
        Spacer(Modifier.height(8.dp))
        SettingRow(
            title = stringResource(R.string.settings_access_title),
            subtitle = stringResource(
                if (accessGranted) R.string.settings_access_on else R.string.settings_access_off
            ),
            action = stringResource(
                if (accessGranted) R.string.settings_change else R.string.access_turn_on
            ),
            onAction = onGrantAccess,
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.settings_this_device))
        Spacer(Modifier.height(8.dp))
        SettingRow(
            title = stringResource(R.string.settings_stop_title),
            subtitle = stringResource(R.string.settings_stop_body),
            action = stringResource(R.string.settings_stop_action),
            onAction = onStopService,
        )
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                text = stringResource(R.string.settings_worker),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = version,
                style = MaterialTheme.typography.labelMedium,
                color = Ink.ink,
            )
        }
        HorizontalDivider(color = Ink.line)

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = Ink.muted,
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.ink,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.muted,
                )
            }
            TextButton(onClick = onAction) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Forest.green,
                )
            }
        }
        HorizontalDivider(color = Ink.line)
    }
}
