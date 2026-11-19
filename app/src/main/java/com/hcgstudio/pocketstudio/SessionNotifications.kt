package com.hcgstudio.pocketstudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

internal const val OPEN_AGENT = "com.hcgstudio.pocketstudio.OPEN_AGENT"
internal const val OPEN_DEBUG = "com.hcgstudio.pocketstudio.OPEN_DEBUG"
internal const val DEBUG_COMMAND = "com.hcgstudio.pocketstudio.DEBUG_COMMAND"

private fun Context.sessionNotice(channel: String, title: Int, icon: Int, open: String): Notification.Builder {
    getSystemService(NotificationManager::class.java)!!.createNotificationChannel(
        NotificationChannel(channel, getString(title), NotificationManager.IMPORTANCE_LOW))
    val activity = Intent(this, MainActivity::class.java).setAction(open)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return Notification.Builder(this, channel).setSmallIcon(icon).setOnlyAlertOnce(true)
        .setShowWhen(false).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
        .setContentIntent(PendingIntent.getActivity(this, 0, activity, PendingIntent.FLAG_IMMUTABLE))
        .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
}

// Calls and service callbacks run on the main thread. Keep only the latest state while Android starts the service.
internal class SessionNotice(val id: Int, private val serviceClass: Class<out Service>) {
    var service: SessionNoticeService? = null
    var active = false
        private set
    var latest: Notification? = null
        private set

    fun publish(context: Context, notification: Notification) {
        val starting = !active
        active = true
        latest = notification
        val manager = context.getSystemService(NotificationManager::class.java)!!
        if (starting) {
            try {
                context.startForegroundService(Intent(context, serviceClass))
            } catch (e: IllegalStateException) {
                // A turn may finish preparation after the user has already backgrounded Studio.
                Log.i("SessionNotice", "Foreground start unavailable", e)
                manager.notify(id, notification)
            }
        } else manager.notify(id, notification)
    }

    fun clear(context: Context) {
        if (!active) return
        active = false
        service?.finishSession()
        context.getSystemService(NotificationManager::class.java)!!.cancel(id)
    }
}

abstract class SessionNoticeService : Service() {
    internal abstract val notice: SessionNotice
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notice.service = this
        val notification = notice.latest
        if (notification != null) startForeground(notice.id, notification)
        if (!notice.active || notification == null) finishSession()
        return START_NOT_STICKY
    }
    internal fun finishSession() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { finishSession() }
    override fun onDestroy() {
        if (notice.service === this) notice.service = null
        super.onDestroy()
    }
}

class AgentRunService : SessionNoticeService() {
    internal override val notice get() = AgentRunNotifications.notice
}
internal object AgentRunNotifications {
    val notice = SessionNotice(7110, AgentRunService::class.java)
    private var last: Pair<String, String>? = null

    fun update(context: Context, provider: String, status: String?) {
        if (status == null) { last = null; notice.clear(context); return }
        val summary = status.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(96)
        val value = provider to summary
        if (value == last) return
        last = value
        notice.publish(context, context.sessionNotice("agent-runs", R.string.agent_run_channel,
            R.drawable.studio_code, OPEN_AGENT).setContentTitle(provider)
            .setContentText(summary.ifBlank { context.getString(R.string.agent_run_channel) }).build())
    }
}

class DebugSessionService : SessionNoticeService() {
    internal override val notice get() = DebugNotifications.notice
}
internal object DebugNotifications {
    val notice = SessionNotice(7111, DebugSessionService::class.java)
    fun update(context: Context, process: String, session: Int, paused: Boolean, attached: Boolean) {
        val builder = context.sessionNotice("debug-sessions", R.string.debug, R.drawable.studio_bug_report, OPEN_DEBUG)
            .setContentTitle(process).setContentText(context.getString(when {
                paused -> R.string.debug_paused
                attached -> R.string.debug_running
                else -> R.string.attaching
            }))
        fun action(command: String, label: Int) {
            val intent = Intent(DEBUG_COMMAND).setPackage(context.packageName)
                .putExtra("session", session).putExtra("command", command)
            builder.addAction(Notification.Action.Builder(null, context.getString(label),
                PendingIntent.getBroadcast(context, 31 * session + command.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)).build())
        }
        if (paused) {
            action("resume", R.string.resume)
            action("step", R.string.step_over)
        }
        if (attached) action("detach", R.string.detach_debugger)
        notice.publish(context, builder.build())
    }
}
