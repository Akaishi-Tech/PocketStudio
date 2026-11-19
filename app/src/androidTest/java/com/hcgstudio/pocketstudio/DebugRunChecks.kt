package com.hcgstudio.pocketstudio

import com.hcgstudio.pocketstudio.plugin.RemoteConnection

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import java.io.File
import java.util.concurrent.FutureTask

/** Exercises the same controller entry points as the gutter, Run menu, and notification buttons. */
internal object DebugRunChecks {
    @JvmStatic fun run(test: Instrumentation, port: String, line: Int) {
        val context = test.targetContext
        val path = "test:debug-run"
        val cls = "dev.ondevice.studio.debuggee.BreakpointActivity"
        fun <T> main(action: () -> T): T {
            val result = FutureTask(action)
            test.runOnMainSync(result)
            return result.get()
        }
        val controller = main { DeviceController(context) }
        val manager = context.getSystemService(NotificationManager::class.java)!!
        fun notification(id: Int) = manager.activeNotifications.firstOrNull { it.id == id }?.notification
        fun waitFor(label: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 40_000L
            while (SystemClock.elapsedRealtime() < end) {
                if (main(predicate)) return
                SystemClock.sleep(100)
            }
            error("$label timed out: ${main { controller.status + " " + controller.output }}")
        }
        try {
            main {
                controller.selectBreakpointProject(path)
                if (line !in controller.markersFor(cls)) controller.toggleBreakpoint(cls, line)
                controller.selectBreakpointProject(null)
                check(controller.markersFor(cls).isEmpty())
                controller.selectBreakpointProject(path)
                check(line in controller.markersFor(cls))
                controller.connectRemote(RemoteConnection.TCP, "127.0.0.1", port.toInt())
            }
            waitFor("ADB connection") { controller.connected && !controller.busy }
            main { controller.install(File(context.filesDir, "studio-test-target.apk"), true, debug = true) }
            waitFor("startup breakpoint") { controller.paused && !controller.busy && controller.output.contains("onCreate:$line") }
            val paused = checkNotNull(notification(DebugNotifications.notice.id))
            val step = paused.actions.first { it.title == context.getString(R.string.step_over) }
            step.actionIntent.send()
            waitFor("notification step") { controller.paused && !controller.busy && controller.output.contains("onCreate:${line + 1}") }
            val resume = checkNotNull(notification(DebugNotifications.notice.id)).actions.first { it.title == context.getString(R.string.resume) }
            resume.actionIntent.send()
            waitFor("notification resume") { !controller.paused && !controller.busy }
            val detach = checkNotNull(notification(DebugNotifications.notice.id)).actions.first { it.title == context.getString(R.string.detach_debugger) }
            detach.actionIntent.send()
            waitFor("notification detach") { !controller.attached && notification(DebugNotifications.notice.id) == null }
            main {
                check(line in controller.markersFor(cls))
                controller.toggleBreakpoint(cls, line)
                AgentRunNotifications.update(context, "Test provider", "Reading project")
            }
            waitFor("ongoing agent notification") {
                notification(AgentRunNotifications.notice.id)?.let { it.flags and Notification.FLAG_ONGOING_EVENT != 0 } == true
            }
            main { AgentRunNotifications.update(context, "Test provider", "Writing reply") }
            waitFor("updated agent notification") {
                notification(AgentRunNotifications.notice.id)?.extras?.getString(Notification.EXTRA_TEXT) == "Writing reply"
            }
        } finally {
            main { AgentRunNotifications.update(context, "Test provider", null); controller.close() }
        }
        waitFor("notification cleanup") { notification(AgentRunNotifications.notice.id) == null }
    }
}
