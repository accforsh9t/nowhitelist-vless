package com.nowlhitelist.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tim.basevpn.refactor.NotificationManagerContract

class NowhitelistNotificationManager(
    private val service: Service
) : NotificationManagerContract {
    private val systemNotificationManager =
        requireNotNull(service.getSystemService(NotificationManager::class.java))

    private val notificationId = 1

    @Volatile
    private var channelCreated = false

    @Volatile
    private var lastText = "VPN service running"

    override fun startNotification() {
        ensureChannelExists()
        service.startForeground(notificationId, buildNotification(lastText))
    }

    override fun updateNotification(notification: Notification) {
        val text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: lastText
        updateNotificationWithText(text)
    }

    override fun updateNotificationWithText(text: String) {
        ensureChannelExists()
        lastText = text
        systemNotificationManager.notify(notificationId, buildNotification(text))
    }

    override fun stopNotification() {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun ensureChannelExists() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channelCreated) return

        synchronized(this) {
            if (channelCreated) return

            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN service status"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            systemNotificationManager.createNotificationChannel(channel)
            channelCreated = true
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPendingIntent = PendingIntent.getActivity(
            service,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle("Nowhitelist VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        private const val CHANNEL_ID = NotificationManagerContract.VPN_NOTIFICATION_CHANNEL
    }
}
