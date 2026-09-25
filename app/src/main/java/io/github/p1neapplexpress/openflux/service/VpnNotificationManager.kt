package io.github.p1neapplexpress.openflux.service

import android.app.Notification

import android.app.NotificationChannel

import android.app.NotificationManager

import android.app.PendingIntent

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat

import android.net.TrafficStats

import android.os.Build

import android.os.Handler

import android.os.Looper

import android.os.Process

import android.os.SystemClock

import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

import io.github.p1neapplexpress.openflux.R

import io.github.p1neapplexpress.openflux.event.AppEvent

import io.github.p1neapplexpress.openflux.event.EventBus

import io.github.p1neapplexpress.openflux.ui.MainActivity

import io.github.p1neapplexpress.openflux.util.AppIconManager
import io.github.p1neapplexpress.openflux.util.AppSettings

import java.util.Locale

class VpnNotificationManager(private val service: Service) {

    companion object {

        const val CHANNEL_ID = "io.github.p1neapplexpress.libp1npplydtransport.so.vpn"

        const val NOTIFICATION_ID = 1

        private const val UPDATE_INTERVAL_MS = 1000L

    }

    private val handler = Handler(Looper.getMainLooper())

    private val uid = Process.myUid()

    private var lastRxBytes = 0L

    private var lastTxBytes = 0L

    private var lastSampleAt = 0L
    @Volatile private var currentRxRate = 0L
    @Volatile private var currentTxRate = 0L

    private val appSettings = AppSettings(service)

    var tunnelName: String? = null
    var lastPingResultMs: Long? = null

    private val speedUpdater = object : Runnable {

        override fun run() {

            val now = SystemClock.elapsedRealtime()

            val elapsedMs = (now - lastSampleAt).coerceAtLeast(1)

            val rawRx = TrafficStats.getUidRxBytes(uid)
            val rawTx = TrafficStats.getUidTxBytes(uid)
            val rxBytes = if (rawRx != TrafficStats.UNSUPPORTED.toLong()) rawRx else lastRxBytes
            val txBytes = if (rawTx != TrafficStats.UNSUPPORTED.toLong()) rawTx else lastTxBytes

            val rxPerSec = if (lastRxBytes > 0 && rxBytes >= lastRxBytes) {
                (rxBytes - lastRxBytes) * 1000 / elapsedMs
            } else 0L

            val txPerSec = if (lastTxBytes > 0 && txBytes >= lastTxBytes) {
                (txBytes - lastTxBytes) * 1000 / elapsedMs
            } else 0L

            lastRxBytes = rxBytes

            lastTxBytes = txBytes

            currentRxRate = rxPerSec
            currentTxRate = txPerSec
            lastSampleAt = now

            EventBus.dispatch(AppEvent.SpeedUpdate(rxPerSec, txPerSec))

            val speedText = if (appSettings.showNotificationSpeed) {

                "↓ ${formatSpeed(rxPerSec)}   ↑ ${formatSpeed(txPerSec)}"

            } else {

                null

            }

            updateContent(service.getString(R.string.running), speedText)

            handler.postDelayed(this, UPDATE_INTERVAL_MS)

        }

    }

    fun startForeground(name: String? = null) {

        if (!name.isNullOrBlank()) {

            tunnelName = name

        }

        createChannel()
        val notification = buildNotification(service.getString(R.string.running))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, fgsType)
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun startSpeedUpdates() {

        lastRxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)

        lastTxBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)

        lastSampleAt = SystemClock.elapsedRealtime()

        handler.removeCallbacks(speedUpdater)

        handler.post(speedUpdater)

    }

    fun updatePing(pingMs: Long) {
        lastPingResultMs = pingMs
        val speedText = if (appSettings.showNotificationSpeed) {
            "↓ ${formatSpeed(currentRxRate)}   ↑ ${formatSpeed(currentTxRate)}"
        } else {
            null
        }
        updateContent(service.getString(R.string.running), speedText)
    }

    fun stopSpeedUpdates() {
        lastPingResultMs = null

        handler.removeCallbacks(speedUpdater)

        EventBus.dispatch(AppEvent.SpeedUpdate(0L, 0L))

    }

    private fun updateContent(status: String, speedText: String? = null) {

        val mgr = service.getSystemService(NotificationManager::class.java) ?: return

        mgr.notify(NOTIFICATION_ID, buildNotification(status, speedText))

    }

    private fun buildNotification(status: String, speedText: String? = null): Notification {
        val isLight = AppIconManager.getCurrentIcon(service) == AppIconManager.ICON_LIGHT
        val targetAlias = if (isLight) {
            "io.github.p1neapplexpress.openflux.ui.MainActivityLight"
        } else {
            "io.github.p1neapplexpress.openflux.ui.MainActivityDark"
        }
        val mainIntent = Intent().setComponent(ComponentName(service, targetAlias)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            service,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = Intent(service, SocksVpnService::class.java).apply {
            action = SocksVpnService.ACTION_DISCONNECT
        }

        val disconnectPending = PendingIntent.getService(
            service,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (!tunnelName.isNullOrBlank()) {
            tunnelName
        } else {
            service.getString(R.string.notify_title)
        }

        val pingStr = when (val p = lastPingResultMs) {
            null -> null
            -1L -> service.getString(R.string.notify_ping_testing)
            else -> service.getString(R.string.notify_ping_format, p)
        }
        val statusAndSpeed = if (!speedText.isNullOrBlank() && appSettings.showNotificationSpeed) {
            "$status • $speedText"
        } else {
            status
        }
        val primaryText = if (!pingStr.isNullOrBlank()) {
            "$statusAndSpeed\n$pingStr"
        } else {
            statusAndSpeed
        }

        val checkPingIntent = Intent(service, SocksVpnService::class.java).apply {
            action = SocksVpnService.ACTION_CHECK_PING
        }
        val checkPingPending = PendingIntent.getService(
            service,
            2,
            checkPingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val accentColor = if (isLight) {
            ContextCompat.getColor(service, R.color.ic_launcher_background_light)
        } else {
            ContextCompat.getColor(service, R.color.ic_launcher_background_dark)
        }

        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusAndSpeed)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accentColor)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_speed, service.getString(R.string.action_check_ping), checkPingPending)
            .addAction(R.drawable.ic_power, service.getString(R.string.action_disconnect), disconnectPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!pingStr.isNullOrBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(primaryText))
        }

        return builder.build()

    }

    fun refresh() {
        val notif = buildNotification(service.getString(R.string.running))
        val mgr = service.getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIFICATION_ID, notif)
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {

        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"

        bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)

        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))

    }

    private fun createChannel() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val mgr = service.getSystemService(NotificationManager::class.java) ?: return

        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(

            CHANNEL_ID,

            service.getString(R.string.channel_name),

            NotificationManager.IMPORTANCE_LOW,

        ).apply {

            description = service.getString(R.string.notify_msg)

            setShowBadge(false)

        }

        mgr.createNotificationChannel(ch)

    }

}
