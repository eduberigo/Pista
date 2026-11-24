package com.eduberigo.pista

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process

class ProcessingService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val nm = getSystemService(NotificationManager::class.java)!!
            nm.notify(ID, notif())
            Pista.main.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CH, "Processamento de músicas", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notif())
        if (!running) {
            running = true
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pista:processamento").apply { acquire() }
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    Processor.run(applicationContext)
                } finally {
                    Pista.main.post {
                        running = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }, "pista-proc").start()
            Pista.main.postDelayed(ticker, 2000)
        }
        return START_NOT_STICKY
    }

    private fun notif(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val p = (Processor.progress.floatValue * 100).toInt()
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(Processor.status.value)
            .setContentText(Processor.detail.value)
            .setProgress(100, p, p == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        running = false
        runCatching { wake?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CH = "proc"
        private const val ID = 1
        @Volatile var running = false
    }
}
