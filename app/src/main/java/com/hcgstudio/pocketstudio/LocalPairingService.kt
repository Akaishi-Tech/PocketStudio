package com.hcgstudio.pocketstudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.hcgstudio.pocketstudio.wireless.LocalWirelessAdb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.UUID

/** Owns discovery and notification replies while Settings is in front of Studio. */
class LocalPairingService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private val session = UUID.randomUUID().toString()
    private var active = false
    private var port = 0
    private var pairing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == START && !active -> startDiscovery()
            active && intent?.getStringExtra(SESSION) == session -> when (intent.action) {
                CANCEL -> finishSession()
                REPLY -> submit(intent)
            }
            !active -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startDiscovery() {
        active = true
        manager(this).cancel(RESULT_ID)
        startForeground(ONGOING_ID, notification(getString(R.string.local_pairing_search)))
        // End before the platform short-service deadline, also on releases without onTimeout().
        main.postDelayed({ if (active) finishSession(getString(R.string.local_pairing_expired)) }, 165_000)
        scope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    LocalWirelessAdb.watchPairing(this@LocalPairingService) { found ->
                        main.post {
                            if (active && found != port) {
                                port = found
                                if (!pairing) update()
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (active) finishSession(error.localizedMessage ?: getString(R.string.local_discovery_unavailable))
            }
        }
    }

    private fun submit(intent: Intent) {
        if (pairing) return
        if (port == 0 || intent.getIntExtra(PORT, 0) != port) {
            update()
            return
        }
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString()?.trim().orEmpty()
        if (!code.matches(Regex("[0-9]{6}"))) {
            update(getString(R.string.enter_the_six_digit_pairing_code_shown_in))
            return
        }
        val pairingPort = port
        pairing = true
        update(getString(R.string.pairing_with_this_device))
        scope.launch {
            try {
                runInterruptible(Dispatchers.IO) { LocalWirelessAdb.pair(this@LocalPairingService, code, pairingPort) }
                if (active) {
                    finishSession(getString(R.string.paired_successfully))
                    getSharedPreferences("studio", MODE_PRIVATE).edit()
                        .putBoolean("localAdbPaired", true)
                        .putBoolean(CONNECT_PENDING, true)
                        .apply()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                pairing = false
                if (active) update(getString(R.string.local_pairing_retry))
            }
        }
    }

    private fun update(message: String = getString(if (port == 0) R.string.local_pairing_search else R.string.keep_the_system_pairing_dialog_open_and_enter)) {
        post(this, ONGOING_ID, notification(message))
    }

    private fun actionIntent(action: String, mutable: Boolean): PendingIntent {
        val intent = Intent(this, LocalPairingService::class.java).setAction(action)
            .putExtra(SESSION, session).putExtra(PORT, port)
        val flags = when {
            !mutable -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
            else -> 0
        }
        return PendingIntent.getService(this, if (mutable) port else 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or flags)
    }

    private fun notification(message: String): Notification {
        val builder = builder(this, message).setOngoing(true)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (port != 0 && !pairing) {
            val input = RemoteInput.Builder(CODE).setLabel(getString(R.string.six_digit_pairing_code)).build()
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.studio_keyboard),
                getString(R.string.enter_pairing_code), actionIntent(REPLY, mutable = true))
                .addRemoteInput(input).setAllowGeneratedReplies(false).build())
        }
        builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.studio_close),
            getString(R.string.cancel), actionIntent(CANCEL, mutable = false)).build())
        return builder.build()
    }

    private fun finishSession(message: String? = null) {
        active = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (message != null) result(this, message)
        stopSelf()
    }

    override fun onTimeout(startId: Int) = finishSession(getString(R.string.local_pairing_expired))
    override fun onTimeout(startId: Int, fgsType: Int) = finishSession(getString(R.string.local_pairing_expired))

    override fun onDestroy() {
        active = false
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        internal const val CONNECT_PENDING = "localAdbConnectPending"
        private const val CHANNEL = "local-wireless-pairing"
        private const val ONGOING_ID = 7106
        private const val RESULT_ID = 7107
        private const val START = "localPairing.start"
        private const val REPLY = "localPairing.reply"
        private const val CANCEL = "localPairing.cancel"
        private const val SESSION = "session"
        private const val PORT = "port"
        private const val CODE = "pairingCode"

        private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)!!

        internal fun notificationSettings(context: Context): Intent? {
            val manager = manager(context)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.local_wireless_pairing),
                NotificationManager.IMPORTANCE_DEFAULT))
            val appBlocked = !manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            val intent = when {
                appBlocked -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE ->
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
                else -> return null
            }
            return intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

        internal fun start(context: Context) {
            context.startForegroundService(Intent(context, LocalPairingService::class.java).setAction(START))
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, LocalPairingService::class.java))
            manager(context).cancel(RESULT_ID)
        }

        internal fun result(context: Context, message: String) {
            post(context, RESULT_ID, builder(context, message).setAutoCancel(true).setTimeoutAfter(5 * 60_000L).build())
        }

        @SuppressLint("MissingPermission")
        private fun post(context: Context, id: Int, notification: Notification) {
            if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                manager(context).notify(id, notification)
            }
        }

        private fun builder(context: Context, message: String): Notification.Builder {
            val activity = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.studio_bug_report)
                .setContentTitle(context.getString(R.string.debug_this_device))
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(context, 0, activity, PendingIntent.FLAG_IMMUTABLE))
        }
    }
}

internal fun openSettings(context: Context, intent: Intent): Boolean {
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
