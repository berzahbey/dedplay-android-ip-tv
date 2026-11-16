package com.iptv.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Kayitlarin uygulama arka plandayken de surmesi icin on plan servisi.
 * Planli kayit ya da suren kayit kalmayinca kendini kapatir.
 */
class RecordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        lastStartId = startId
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dedplay:record").also {
                it.setReferenceCounted(false)
                it.acquire(12 * 60 * 60 * 1000L)
            }
        }
        if (!Recorder.loopActive) {
            Recorder.attach(
                applicationContext, scope,
                onUpdate = { updateNotification() },
                // Bu arada yeni bir baslatma istegi geldiyse (yeni kayit) servis kapanmaz
                onIdle = { if (!Recorder.loopActive) stopSelf(lastStartId) }
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Recorder.detach()
        scope.cancel()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kayıtlar", NotificationManager.IMPORTANCE_LOW))
        }
        val running = Recorder.running.values
        val planned = Recorder.scheduled.size
        val text = when {
            running.isNotEmpty() -> "Kaydediliyor: " + running.joinToString(", ") { it.job.title.ifEmpty { it.job.channel } }
            planned > 0 -> "$planned planlı kayıt bekliyor"
            else -> "Kayıt servisi"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("dedplay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    companion object {
        private const val CHANNEL = "recordings"
        private const val NOTIF_ID = 4711
    }
}
