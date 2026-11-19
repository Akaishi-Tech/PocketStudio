package com.hcgstudio.pocketstudio

import android.graphics.Bitmap
import com.hcgstudio.pocketstudio.adb.Target
import com.hcgstudio.pocketstudio.agent.AgentToolContent
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal object DeviceAgentTools {
    private const val LOG_WINDOW_SECONDS = 600
    private const val LOG_BYTES = 32000
    private const val LOG_FAILURE = "STUDIO_LOGCAT_COMMAND_FAILED"
    private const val IMAGE_BYTES = 1024 * 1024
    private val packageUid = Regex("""package:(\S+) uid:(\d+)""")
    private val applicationId = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    // The owner closes just this multiplexed stream when cancellation interrupts await().
    private suspend fun output(adb: Target, command: String, limit: Int): ByteArray = coroutineScope {
        adb.open("exec:$command").use { stream ->
            async {
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val input = stream.input()
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > limit - result.size()) throw IOException(uiString(R.string.target_output_is_too_long))
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }.await()
        }
    }

    suspend fun layoutXml(adb: Target): String =
        output(adb, DeviceInspector.hierarchyCommand(), 4 * 1024 * 1024).toString(Charsets.UTF_8)

    suspend fun screenshot(adb: Target): List<AgentToolContent> {
        val bytes = output(adb, DeviceInspector.SCREEN_COMMAND, DeviceInspector.MAX_SCREEN_BYTES)
        currentCoroutineContext().ensureActive()
        val screen = DeviceInspector.decodeScreen(bytes)
        var bitmap = screen.bitmap
        try {
            val image = if (bytes.size <= IMAGE_BYTES && bitmap.width == screen.width && bitmap.height == screen.height) {
                AgentToolContent.Image("image/png", bytes)
            } else {
                // Preserve as much detail as the transport budget permits, even for noisy screens.
                val encoded = ByteArrayOutputStream()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, encoded))
                        throw IOException(uiString(R.string.unable_to_parse_the_screenshot))
                    if (encoded.size() <= IMAGE_BYTES) break
                    val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width / 2).coerceAtLeast(1),
                        (bitmap.height / 2).coerceAtLeast(1), true)
                    bitmap.recycle()
                    bitmap = scaled
                    encoded.reset()
                }
                AgentToolContent.Image("image/jpeg", encoded.toByteArray())
            }
            currentCoroutineContext().ensureActive()
            val size = json("screenWidth" to screen.width, "screenHeight" to screen.height,
                "imageWidth" to bitmap.width, "imageHeight" to bitmap.height)
            return listOf(AgentToolContent.Text(size.toString()), image)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun act(adb: Target, args: JSONObject, beforeInput: () -> Unit = {}): String {
        val command = deviceInputCommand(args)
        currentCoroutineContext().ensureActive()
        beforeInput()
        // exec: supplies bytes without an exit status. Confirm every input command completed.
        val result = output(adb, "$command && echo STUDIO_INPUT_OK", 8192).toString(Charsets.UTF_8).trim()
        if (result != "STUDIO_INPUT_OK") throw IOException(uiString(R.string.agent_device_input_failed, result.take(500)))
        return json("action" to args.getString("action"), "ok" to true).toString()
    }

    suspend fun logs(adb: Target, pkg: String, maxLines: Int): String {
        require(pkg.length <= 255 && applicationId.matches(pkg)) { uiString(R.string.enter_a_valid_app_package_name) }
        require(maxLines in 1..400) { uiString(R.string.agent_log_line_range) }
        // The installed UID survives process exits and includes the app's secondary processes.
        val packages = output(adb, "cmd package list packages -U --user \$(am get-current-user)", 1024 * 1024)
            .toString(Charsets.UTF_8).lineSequence().mapNotNull { packageUid.matchEntire(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
        if (packages.isEmpty()) throw IOException(uiString(R.string.agent_device_packages_unavailable))
        val uid = packages[pkg] ?: throw IOException(uiString(R.string.agent_package_not_installed, pkg))
        if (packages.any { (name, value) -> value == uid && name != pkg }) {
            throw IOException(uiString(R.string.agent_package_shared_uid, pkg))
        }
        // Use the device clock. Tail after UID filtering so busy unrelated apps do not hide this app's crash.
        val args = "-b main -b system -b crash -v threadtime -t \"\$start.000\""
        // Older logcat (including Android 8) prints the UID field but has no --uid option.
        // With epoch formatting the first two columns are the timestamp and numeric UID or short Android ID.
        val uidLine = "^ *[0-9]+[.][0-9]+ +($uid|\${name:-$uid}) +[0-9]+ +[0-9]+ "
        val command = "start=\$((\$(date +%s) - $LOG_WINDOW_SECONDS)); " +
            "if logcat --help 2>&1 | grep -q -e '--uid'; then " +
            "logcat --uid=$uid $args 2>&1 || echo $LOG_FAILURE; " +
            "else name=\$(id -nu $uid 2>/dev/null); " +
            "{ logcat $args -v epoch -v uid 2>/dev/null || echo $LOG_FAILURE; } " +
            "| grep -E \"$uidLine|^$LOG_FAILURE$\"; fi " +
            "| sed '/^--------- /d' | tail -n ${maxLines + 1} | tail -c ${LOG_BYTES + 1}"
        val bytes = output(adb, command, LOG_BYTES + 1)
        val text = bytes.toString(Charsets.UTF_8).trimEnd('\r', '\n')
        if (text.lineSequence().lastOrNull()?.trim() == LOG_FAILURE) {
            throw IOException(uiString(R.string.agent_logcat_failed, text.substringBeforeLast(LOG_FAILURE).trim().take(500)))
        }
        val clipped = bytes.size > LOG_BYTES
        val wholeLines = (if (clipped) text.substringAfter('\n', "") else text).lineSequence()
            .map { it.trimEnd('\r') }.filter(String::isNotEmpty).toList()
        return json("packageName" to pkg, "uid" to uid.toInt(), "windowSeconds" to LOG_WINDOW_SECONDS,
            "lines" to JSONArray(wholeLines.takeLast(maxLines)),
            "truncated" to (clipped || wholeLines.size > maxLines)).toString()
    }
}
