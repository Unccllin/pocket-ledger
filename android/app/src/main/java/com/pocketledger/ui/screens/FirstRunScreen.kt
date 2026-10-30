package com.pocketledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pocketledger.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketledger.ui.design.Forest
import com.pocketledger.ui.design.Ink

/**
 * First run: one job, said plainly. Why the app needs Notification Access,
 * what it reads, and what it never does.
 */
@Composable
fun FirstRunScreen(
    accessGranted: Boolean,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.first_run_title),
            style = MaterialTheme.typography.headlineMedium,
            color = Ink.ink,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                if (accessGranted) R.string.first_run_access_on else R.string.first_run_access_off
            ),
            style = MaterialTheme.typography.titleMedium,
            color = Ink.ink,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(20.dp))

        Point(stringResource(R.string.first_run_point_reads))
        Point(stringResource(R.string.first_run_point_never))
        Point(stringResource(R.string.first_run_point_privacy))
        Point(stringResource(R.string.first_run_point_background))

        Spacer(Modifier.height(28.dp))
        if (accessGranted) {
            Button(
                onClick = onContinue,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.first_run_continue),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        } else {
            Button(
                onClick = onOpenSettings,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.first_run_open_settings),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.first_run_come_back),
                style = MaterialTheme.typography.bodySmall,
                color = Ink.muted,
            )
        }
    }
}

@Composable
private fun Point(text: String, color: Color = Ink.muted) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    )
}

/** A quiet "granted" marker, used on Settings once the listener is allowed. */
@Composable
fun GrantedBadge() {
    Text(
        text = "On",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = Forest.green,
    )
}
