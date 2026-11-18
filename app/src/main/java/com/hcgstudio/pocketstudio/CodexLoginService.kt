package com.hcgstudio.pocketstudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.MainThread
import java.io.Closeable

/** Keeps the CLI's UID eligible for network access while the browser handles sign-in. */
class CodexLoginService : Service() {
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)!!
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.codex_login_channel),
            NotificationManager.IMPORTANCE_LOW))
        val activity = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.studio_account)
            .setContentTitle(getString(R.string.codex_login_channel))
            .setContentText(getString(R.string.codex_login_notification))
            .setContentIntent(PendingIntent.getActivity(this, 0, activity, PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        // Android still expects promotion when the caller cancels before this callback.
        startForeground(NOTIFICATION_ID, notification)
        latestStartId = startId
        running = this
        if (loginCount == 0) stopIfCurrent()
        return START_NOT_STICKY
    }

    private fun stopIfCurrent() {
        if (stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "codex-sign-in"
        private const val NOTIFICATION_ID = 7108
        private var loginCount = 0
        private var running: CodexLoginService? = null

        /** Acquire and close on the main thread, which also delivers the service callbacks. */
        @MainThread
        internal fun start(context: Context): Closeable {
            if (loginCount == 0) {
                context.startForegroundService(Intent(context, CodexLoginService::class.java))
            }
            loginCount++
            var closed = false
            return Closeable {
                if (!closed) {
                    closed = true
                    loginCount--
                    if (loginCount == 0) running?.stopIfCurrent()
                }
            }
        }
    }
}
