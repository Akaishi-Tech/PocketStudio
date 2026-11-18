package com.hcgstudio.pocketstudio

import com.hcgstudio.pocketstudio.adb.Target
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray

internal object DeviceAgentTools {
    private const val LOG_WINDOW_SECONDS = 600
    private const val LOG_BYTES = 32000
    private const val LOG_FAILURE = "STUDIO_LOGCAT_COMMAND_FAILED"
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

    suspend fun inspect(adb: Target): String {
        val xml = output(adb, DeviceInspector.hierarchyCommand(), 4 * 1024 * 1024).toString(Charsets.UTF_8)
        return DeviceInspector.hierarchyForAgent(xml).toString()
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
