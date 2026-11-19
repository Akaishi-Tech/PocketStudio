package com.hcgstudio.pocketstudio

import com.hcgstudio.pocketstudio.plugin.RemoteConnection

import android.content.Context
import android.util.Log
import com.hcgstudio.pocketstudio.adb.Target
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

object DeviceAgentChecks {
    private class FakeTarget(val reply: (String) -> InputStream) : Target {
        override fun open(service: String): Target.Stream {
            val input = reply(service)
            return object : Target.Stream {
                override fun input() = input
                override fun output() = ByteArrayOutputStream()
                override fun close() = input.close()
            }
        }
        override fun shell(command: String): String = error(command)
        override fun install(apk: InputStream, size: Long) = error("Unexpected install")
        override fun isConnected() = true
        override fun close() = Unit
    }

    @JvmStatic fun run() = runBlocking(Dispatchers.IO) {
        val uid = "10101"
        val pkg = "com.example.test"
        val target = FakeTarget { service ->
            when {
                "list packages" in service -> "package:${pkg}x uid:10001\npackage:$pkg uid:$uid\n"
                "logcat --uid=$uid " in service -> {
                    check("-b crash" in service && "-t " in service && "tail -n 3" in service)
                    "oldest\nFATAL EXCEPTION: main\n    at Example.main(Example.java:2)\n"
                }
                else -> error(service)
            }.byteInputStream()
        }
        val result = JSONObject(DeviceAgentTools.logs(target, pkg, 2))
        check(result.getString("packageName") == pkg && result.getBoolean("truncated"))
        check(result.getJSONArray("lines").getString(1).startsWith("    at Example"))
        check(runCatching { DeviceAgentTools.logs(target, "com.example.missing", 2) }.exceptionOrNull() is IOException)
        check(runCatching { DeviceAgentTools.logs(target, pkg, 401) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { DeviceAgentTools.logs(target, "com.example;id", 1) }.exceptionOrNull() is IllegalArgumentException)
        val shared = FakeTarget { "package:$pkg uid:$uid\npackage:com.other uid:$uid\n".byteInputStream() }
        check(runCatching { DeviceAgentTools.logs(shared, pkg, 2) }.exceptionOrNull() is IOException)

        val node = """<node class="android.widget.Button" text="Save" resource-id="id/save" bounds="[20,0][80,40]" clickable="true" enabled="true"/>"""
        val password = """<node password="true" text="secret" content-desc="secret" bounds="[0,41][50,50]"/>"""
        fun hierarchy(xml: String) = FakeTarget { service ->
            check("uiautomator dump" in service && "screencap" !in service)
            xml.byteInputStream()
        }
        val ui = JSONObject(DeviceAgentTools.inspect(hierarchy("<hierarchy>$node$password</hierarchy>")))
        val nodes = ui.getJSONArray("nodes")
        check(nodes.length() == 2 && !ui.getBoolean("truncated"))
        val button = nodes.getJSONObject(0)
        check(button.getString("text") == "Save" && button.getJSONArray("bounds").getInt(2) == 80)
        check(button.getInt("depth") == 0 && button.getBoolean("clickable"))
        check(nodes.getJSONObject(1).getBoolean("password") && "secret" !in ui.toString())
        val empty = JSONObject(DeviceAgentTools.inspect(hierarchy("<hierarchy rotation=\"0\"/>\ntrailing shell text")))
        check(empty.getJSONArray("nodes").length() == 0 && !empty.getBoolean("truncated"))
        val many = JSONObject(DeviceAgentTools.inspect(hierarchy("<hierarchy>${node.repeat(300)}</hierarchy>")))
        check(many.getBoolean("truncated") && many.toString().length < 24500)
        check(runCatching { DeviceAgentTools.inspect(hierarchy("UI unavailable")) }.exceptionOrNull() is IOException)

        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stalled = FakeTarget { object : InputStream() {
            override fun read(): Int {
                started.countDown()
                check(closed.await(5, TimeUnit.SECONDS))
                return -1
            }
            override fun close() = closed.countDown()
        } }
        val reading = launch { DeviceAgentTools.inspect(stalled) }
        check(started.await(5, TimeUnit.SECONDS))
        reading.cancelAndJoin()
        check(closed.count == 0L)
    }

    @JvmStatic fun runConnected(context: Context, port: Int) = runBlocking {
        withContext(Dispatchers.Main) {
            val devices = DeviceController(context)
            try {
                check(runCatching { devices.inspectDeviceUi() }.exceptionOrNull() is IOException)
                devices.connectRemote(RemoteConnection.TCP, "127.0.0.1", port)
                withTimeout(45_000) {
                    while (devices.busy) delay(100)
                }
                check(devices.connected) { devices.status }
                val marker = "device-agent-${System.nanoTime()}"
                Log.e("StudioDeviceTools", marker)
                val response = JSONObject(devices.readLogcat(File(context.applicationInfo.sourceDir), null, 150))
                check(response.getString("packageName") == context.packageName)
                check(marker in response.getJSONArray("lines").toString()) { "Target log marker missing" }
                val nodes = JSONObject(devices.inspectDeviceUi()).getJSONArray("nodes")
                check(nodes.length() > 0 && nodes.getJSONObject(0).getJSONArray("bounds").length() == 4)
            } finally {
                devices.close()
            }
        }
    }
}
