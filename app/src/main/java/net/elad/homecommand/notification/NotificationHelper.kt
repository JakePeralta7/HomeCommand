package net.elad.homecommand.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import net.elad.homecommand.R
import net.elad.homecommand.data.Device

object NotificationHelper {
    const val CHANNEL_STATE_CHANGES = "state_changes"
    private const val CHANNEL_BATTERY_ALERTS = "battery_alerts"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val stateChannel =
            NotificationChannel(
                CHANNEL_STATE_CHANGES,
                context.getString(R.string.channel_state_changes),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_state_changes_desc)
            }

        val batteryChannel =
            NotificationChannel(
                CHANNEL_BATTERY_ALERTS,
                context.getString(R.string.channel_battery_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_battery_alerts_desc)
            }

        manager.createNotificationChannel(stateChannel)
        manager.createNotificationChannel(batteryChannel)
    }

    fun notifyStateChanged(
        context: Context,
        device: Device,
        newState: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_STATE_CHANGES)
                .setSmallIcon(R.drawable.ic_rooms)
                .setContentTitle(device.name)
                .setContentText(context.getString(R.string.notification_state_format, newState))
                .setAutoCancel(true)
                .build()
        manager.notify(device.id.hashCode(), notification)
    }

    fun notifyBatteryAlert(
        context: Context,
        device: Device,
        batteryLevel: Int,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_BATTERY_ALERTS)
                .setSmallIcon(R.drawable.ic_rooms)
                .setContentTitle(context.getString(R.string.battery_alert_title))
                .setContentText(context.getString(R.string.battery_alert_format, device.name, batteryLevel))
                .setAutoCancel(true)
                .build()
        // Use a distinct ID from state notifications so both can appear
        manager.notify(device.id.hashCode() + 100000, notification)
    }
}
