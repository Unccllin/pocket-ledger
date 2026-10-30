package com.pocketledger.data

import android.content.Context
import android.provider.Settings

/** True when the user has allowed pocket-ledger to read notifications. */
fun notificationAccessGranted(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: return false
    return enabled.split(':').any { it.startsWith(context.packageName) }
}

/** Deep link to the system screen where Notification Access is granted. */
fun openNotificationAccessSettings(context: Context) {
    context.startActivity(
        android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
