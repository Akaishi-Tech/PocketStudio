package com.hcgstudio.pocketstudio

import com.hcgstudio.pocketstudio.plugin.RemoteConnection

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hcgstudio.pocketstudio.adb.Target
import com.hcgstudio.pocketstudio.agent.AgentToolContent
import com.hcgstudio.pocketstudio.testing.AgentLayoutActivity
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
        val ui = JSONObject(DeviceLayoutActions(hierarchy("<hierarchy>$node$password</hierarchy>")).inspect())
        val nodes = ui.getJSONArray("nodes")
        check(nodes.length() == 2 && !ui.getBoolean("truncated"))
        val button = nodes.getJSONObject(0)
        check(button.getString("text") == "Save" && button.getJSONArray("bounds").getInt(2) == 80)
        check(button.getInt("depth") == 0 && button.getBoolean("clickable"))
        check(nodes.getJSONObject(1).getBoolean("password") && "secret" !in ui.toString())
        val empty = JSONObject(DeviceLayoutActions(hierarchy("<hierarchy rotation=\"0\"/>\ntrailing shell text")).inspect())
        check(empty.getJSONArray("nodes").length() == 0 && !empty.getBoolean("truncated"))
        val many = JSONObject(DeviceLayoutActions(hierarchy("<hierarchy>${node.repeat(300)}</hierarchy>")).inspect())
        check(many.getBoolean("truncated") && many.toString().length < 24500)
        check(runCatching { DeviceLayoutActions(hierarchy("UI unavailable")).inspect() }.exceptionOrNull() is IOException)
        checkLayoutReferences()

        fun png(w: Int, h: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        suspend fun capture(bytes: ByteArray) = DeviceAgentTools.screenshot(FakeTarget { service ->
            check(service == "exec:screencap -p")
            bytes.inputStream()
        })
        val small = png(20, 40)
        val image = capture(small)
        check((image[1] as AgentToolContent.Image).bytes.contentEquals(small))
        val large = capture(png(2400, 1800))
        val dimensions = JSONObject((large[0] as AgentToolContent.Text).text)
        val jpeg = large[1] as AgentToolContent.Image
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg.bytes, 0, jpeg.bytes.size, options)
        check(jpeg.mimeType == "image/jpeg" && jpeg.bytes.size <= 1024 * 1024)
        check(dimensions.getInt("screenWidth") == 2400 && dimensions.getInt("screenHeight") == 1800)
        check(options.outWidth == dimensions.getInt("imageWidth") && options.outWidth < 2400)
        check(options.outHeight == dimensions.getInt("imageHeight") && options.outHeight < 1800)
        check(runCatching { capture("permission denied".toByteArray()) }.exceptionOrNull() is IOException)
        check(runCatching { capture(small.copyOf(small.size - 12)) }.exceptionOrNull() is IOException)

        suspend fun action(args: JSONObject, command: String) {
            val reply = DeviceAgentTools.act(FakeTarget { service ->
                check(service == "exec:$command && echo STUDIO_INPUT_OK") { service }
                "STUDIO_INPUT_OK\n".byteInputStream()
            }, args)
            check(JSONObject(reply).getBoolean("ok"))
        }
        action(json("action" to "tap", "x" to 1, "y" to 2), "input tap 1 2")
        action(json("action" to "long_press", "x" to 1, "y" to 2), "input swipe 1 2 1 2 600")
        action(json("action" to "swipe", "x" to 1, "y" to 2, "endX" to 3, "endY" to 4, "durationMs" to 50),
            "input swipe 1 2 3 4 50")
        action(json("action" to "key", "key" to "BACK"), "input keyevent KEYCODE_BACK")
        action(json("action" to "text", "text" to "a ';\$(id) %s"), "input text 'a '\\'';\$(id) %' && input text 's'")
        for (bad in listOf(json("action" to "text", "text" to "x\ny"),
            json("action" to "key", "key" to "HOME;id"),
            json("action" to "tap", "x" to "1", "y" to 2), json("action" to "tap", "x" to 0.7, "y" to 2),
            json("action" to "tap", "x" to -1, "y" to 1), json("action" to "swipe", "x" to 1, "y" to 2))) {
            check(runCatching { deviceInputCommand(bad) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching {
            DeviceAgentTools.act(FakeTarget { "Permission denied".byteInputStream() }, json("action" to "key", "key" to "BACK"))
        }.exceptionOrNull() is IOException)

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
        val reading = launch { DeviceLayoutActions(stalled).inspect() }
        check(started.await(5, TimeUnit.SECONDS))
        reading.cancelAndJoin()
        check(closed.count == 0L)
    }

    private suspend fun checkLayoutReferences() {
        fun button(bounds: String, enabled: Boolean = true) =
            """<node package="example.app" class="android.widget.Button" resource-id="example.app:id/save" text="Save" clickable="true" enabled="$enabled" bounds="$bounds"/>"""
        var hierarchy = button("[0,0][100,100]")
        val inputs = mutableListOf<String>()
        val layout = DeviceLayoutActions(FakeTarget { service ->
            when {
                "uiautomator dump" in service -> "<hierarchy>$hierarchy</hierarchy>"
                else -> { inputs.add(service); "STUDIO_INPUT_OK" }
            }.byteInputStream()
        })
        suspend fun ref() = JSONObject(layout.inspect()).getJSONArray("nodes").getJSONObject(0).getString("ref")
        suspend fun click(ref: String) = layout.perform("click_device_node", json("ref" to ref))
        val first = ref()
        hierarchy = button("[110,20][210,80]")
        click(first)
        check(inputs.single().contains("input tap 160 50")) { inputs }
        inputs.clear()
        check(runCatching { click(first) }.exceptionOrNull() is IOException)
        val beforeKey = ref()
        layout.input(json("action" to "key", "key" to "BACK"))
        check(runCatching { click(beforeKey) }.exceptionOrNull() is IOException)
        check(inputs.size == 1 && "KEYCODE_BACK" in inputs.single())
        inputs.clear()
        val current = ref()
        check(runCatching { click(first) }.exceptionOrNull() is IOException)
        hierarchy += button("[300,50][400,150]")
        check(runCatching { click(current) }.exceptionOrNull() is IOException)
        suspend fun buttons() = JSONObject(layout.inspect()).getJSONArray("nodes").objects()
        val firstOfTwo = buttons()
        check(firstOfTwo[0].getString("ref") != firstOfTwo[1].getString("ref"))
        click(firstOfTwo[0].getString("ref"))
        click(buttons()[1].getString("ref"))
        check(inputs.size == 2 && "input tap 160 50" in inputs[0] && "input tap 350 100" in inputs[1])
        inputs.clear()
        val ambiguous = ref()
        hierarchy = button("[110,20][210,80]")
        check(runCatching { click(ambiguous) }.exceptionOrNull() is IOException)
        hierarchy += hierarchy
        val overlapping = ref()
        check(runCatching { click(overlapping) }.exceptionOrNull() is IOException)
        hierarchy = button("[110,20][210,80]")
        val unique = ref()
        hierarchy = button("[110,20][210,80]", enabled = false)
        check(runCatching { click(unique) }.exceptionOrNull() is IOException)
        hierarchy = ""
        check(runCatching { click(unique) }.exceptionOrNull() is IOException)
        hierarchy = """<node package="example.app" class="android.widget.EditText" content-desc="Name" enabled="true" bounds="[0,5][200,45]"/>"""
        val field = ref()
        check(runCatching { layout.perform("type_device_node", json("ref" to field, "text" to "x\ny")) }.exceptionOrNull() is IllegalArgumentException)
        check(inputs.isEmpty()) { "An invalid node reference sent input: $inputs" }
        check(runCatching { layout.perform("type_device_node", json("ref" to field, "text" to "private")) }.exceptionOrNull() is IOException)
        check(inputs.size == 1 && inputs.single().contains("input tap")) { "Typed into a field that never gained focus: $inputs" }
        check(runCatching { layout.perform("type_device_node", json("ref" to field, "text" to "retry")) }.exceptionOrNull() is IOException)
        check(inputs.size == 1)
        inputs.clear()
        hierarchy = """<node enabled="true" clickable="true" text="Previous" bounds="[0,0][100,50]"/>
            <node enabled="true" clickable="false" bounds="[0,0][0,0]">
                <node text="Label" enabled="true" clickable="false" bounds="[150,5][210,45]"/>
            </node>"""
        val label = JSONObject(layout.inspect()).getJSONArray("nodes").objects().single { it.optString("text") == "Label" }
        check(!label.getBoolean("clickable"))
        check(runCatching { click(label.getString("ref")) }.exceptionOrNull() is IOException)
        check(inputs.isEmpty())
    }

    fun openLayoutFixture(context: Context) {
        context.startActivity(Intent().setClassName(context.packageName + ".test", AgentLayoutActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
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
                val screen = devices.captureDeviceScreen()
                val size = JSONObject((screen.first() as AgentToolContent.Text).text)
                val image = screen.last() as AgentToolContent.Image
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
                check(options.outMimeType == image.mimeType && options.outWidth == size.getInt("imageWidth"))
                check(options.outHeight == size.getInt("imageHeight") && options.outHeight > 0)

                // The system file picker provides a real editable control. Exit without saving.
                context.startActivity(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setType("text/plain").putExtra(Intent.EXTRA_TITLE, "sample.txt"))
                suspend fun filename() = JSONObject(devices.inspectDeviceUi()).getJSONArray("nodes").objects()
                    .firstOrNull { it.optString("class") == "android.widget.EditText" }
                try {
                    val bounds = withTimeout(15_000) {
                        var field = filename()
                        while (field == null) { delay(100); field = filename() }
                        field.getJSONArray("bounds")
                    }
                    devices.performDeviceAction(json("action" to "tap", "x" to (bounds.getInt(0) + bounds.getInt(2)) / 2,
                        "y" to (bounds.getInt(1) + bounds.getInt(3)) / 2))
                    devices.performDeviceAction(json("action" to "text", "text" to "Cu7"))
                    check("Cu7" in filename()!!.getString("text"))
                    devices.performDeviceAction(json("action" to "key", "key" to "DEL"))
                    val after = filename()!!.getString("text")
                    check("Cu" in after && "Cu7" !in after)
                } finally {
                    devices.performDeviceAction(json("action" to "key", "key" to "BACK"))
                }
                openLayoutFixture(context)
                val ref = withTimeout(15_000) {
                    suspend fun findButton() = JSONObject(devices.inspectDeviceUi()).getJSONArray("nodes").objects()
                        .firstOrNull { it.optString("text") == "Activate" }
                    var button = findButton()
                    while (button == null) { delay(100); button = findButton() }
                    button.getString("ref")
                }
                devices.disconnect()
                val args = json("ref" to ref)
                check(runCatching { devices.performDeviceNodeAction("click_device_node", args) }.exceptionOrNull() is IOException)
                devices.connectRemote(RemoteConnection.TCP, "127.0.0.1", port)
                withTimeout(45_000) { while (devices.busy) delay(100) }
                check(devices.connected)
                check(runCatching { devices.performDeviceNodeAction("click_device_node", args) }.exceptionOrNull() is IOException)
            } finally {
                devices.close()
            }
        }
    }
}
