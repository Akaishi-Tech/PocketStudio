package com.hcgstudio.pocketstudio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import com.hcgstudio.pocketstudio.adb.Target
import com.hcgstudio.pocketstudio.adb.UsbAdb
import com.hcgstudio.pocketstudio.debug.JdwpSession
import com.android.ddmlib.Log.LogLevel
import com.hcgstudio.pocketstudio.wireless.LocalWirelessAdb
import com.hcgstudio.pocketstudio.wireless.WirelessAdb
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface DeviceDialog {
    data object LocalSetup : DeviceDialog
    data class Message(@get:StringRes val text: Int) : DeviceDialog
    data class ListPicker(@get:StringRes val title: Int, val items: List<String>, val select: (Int) -> Unit) : DeviceDialog
    data class Field(@get:StringRes val label: Int, val initial: String = "", val numeric: Boolean = false, val secret: Boolean = false)
    data class Form(@get:StringRes val title: Int, val fields: List<Field>, @get:StringRes val action: Int, val submit: (List<String>) -> Unit) : DeviceDialog
    data class Inspection(val snapshot: DeviceInspector.Snapshot) : DeviceDialog
}

private fun interface DeviceStatus {
    fun resolve(context: Context): String
}

private fun deviceStatus(@StringRes resource: Int, vararg args: Any?) =
    DeviceStatus { it.getString(resource, *args) }

private fun deviceStatus(message: String) = DeviceStatus { message }

internal class DeviceController(context: Context) : Closeable {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("studio", Context.MODE_PRIVATE)
    private val usb = app.getSystemService(UsbManager::class.java)
    private val permissionAction = app.packageName + ".USB_ACCESS"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private var activeJob: Future<*>? = null
    private var generation = 0
    val connectionId get() = generation
    private var attachment = 0
    private var closed = false
    private var target by mutableStateOf<Target?>(null)
    private var debugger by mutableStateOf<JdwpSession?>(null)
    private var waitingUsb: UsbDevice? = null
    private var localTarget = false
    private val pairingResult = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LocalPairingService.CONNECT_PENDING) consumePairingResult()
    }
    private var thread by mutableLongStateOf(0)
    private val breakpoints = mutableStateListOf<Pair<String, Int>>()

    private var deviceDetails by mutableStateOf("")
    val deviceName: String
        get() = when {
            !connected -> ""
            deviceDetails.isEmpty() -> app.getString(if (localTarget) R.string.this_device else R.string.android_device)
            localTarget -> app.getString(R.string.local_device_details, deviceDetails)
            else -> deviceDetails
        }
    var process by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    val connected get() = target != null
    val attached get() = debugger != null
    val paused get() = thread != 0L
    val hasBreakpoints get() = breakpoints.isNotEmpty()
    private var statusMessage by mutableStateOf<DeviceStatus?>(null)
    val status: String get() = statusMessage?.resolve(app).orEmpty()
    private var outputMessage by mutableStateOf(deviceStatus(""))
    var output: String
        get() = outputMessage.resolve(app)
        private set(value) { outputMessage = deviceStatus(value) }
    var dialog by mutableStateOf<DeviceDialog?>(null)

    var logging by mutableStateOf(false)
        private set
    private var live: Target.Stream? = null
    @Volatile private var logVersion = 0
    private val lines = ArrayDeque<String>()
    private var filter = ""
    private var priority = 2

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = waitingUsb ?: return
            if (intent.action != permissionAction || intent.getIntExtra("request", -1) != generation) return
            waitingUsb = null
            busy = false
            if (usb?.hasPermission(device) == true) connect(device)
            else statusMessage = deviceStatus(R.string.usb_access_was_denied)
        }
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (closed) return
            if (target?.isConnected == false) {
                disconnect()
                statusMessage = deviceStatus(R.string.target_device_disconnected)
            }
            main.postDelayed(this, 2000)
        }
    }

    init {
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
        prefs.registerOnSharedPreferenceChangeListener(pairingResult)
        consumePairingResult()
        main.post(monitor)
    }

    private fun closeLater(resource: Closeable?) {
        if (resource == null) return
        Thread({ runCatching { resource.close() } }, "Studio disconnect").start()
    }

    private fun <T> job(progress: DeviceStatus, task: () -> T, done: (T) -> Unit) =
        job(progress, task, {}, done)

    private fun <T> job(progress: DeviceStatus, task: () -> T, onError: (String) -> Unit, done: (T) -> Unit) {
        if (closed || busy) return
        val token = generation
        busy = true
        statusMessage = progress
        activeJob = io.submit {
            try {
                val value = task()
                main.post {
                    if (closed || generation != token) {
                        closeLater(value as? Closeable)
                    } else {
                        activeJob = null
                        busy = false
                        statusMessage = null
                        done(value)
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (!closed && generation == token) {
                        activeJob = null
                        busy = false
                        statusMessage = deviceStatus(e.localizedMessage ?: e.javaClass.simpleName)
                        onError(status)
                    }
                }
            }
        }
    }

    fun disconnect() {
        generation++
        activeJob?.cancel(true)
        activeJob = null
        busy = false
        waitingUsb = null
        localTarget = false
        if (!closed) stopLocalPairing()
        stopLog()
        detach()
        closeLater(target)
        target = null
        deviceDetails = ""
        output = ""
        statusMessage = null
        dialog = null
    }

    override fun close() {
        if (closed) return
        closed = true
        disconnect()
        prefs.unregisterOnSharedPreferenceChangeListener(pairingResult)
        main.removeCallbacks(monitor)
        app.unregisterReceiver(receiver)
        io.shutdownNow()
    }

    private fun identify(adb: Target) {
        target = adb
        job(deviceStatus(R.string.reading_device_information), { adb.shell("getprop ro.product.model; getprop ro.build.version.release") }) {
            val data = it.trim().lineSequence().map(String::trim).toList()
            deviceDetails = listOfNotNull(data.getOrNull(0),
                data.getOrNull(1)?.let { version -> "Android $version" }).joinToString(" · ")
        }
    }

    fun localDevice() {
        if (busy || connected) return
        if (Build.VERSION.SDK_INT < 30) {
            dialog = DeviceDialog.Message(R.string.wireless_debugging_on_this_device_requires_android_11)
            return
        }
        stopLocalPairing()
        statusMessage = null
        if (prefs.getBoolean("localAdbPaired", false)) connectLocal(notify = false)
        else dialog = DeviceDialog.LocalSetup
    }

    private fun stopLocalPairing() {
        LocalPairingService.stop(app)
        prefs.edit().remove(LocalPairingService.CONNECT_PENDING).apply()
    }

    fun localSettings() {
        if (busy || connected || dialog != DeviceDialog.LocalSetup) return
        val notifications = LocalPairingService.notificationSettings(app)
        if (notifications != null) {
            Toast.makeText(app, R.string.local_pairing_enable_notifications, Toast.LENGTH_LONG).show()
            if (!openSettings(app, notifications)) dialog = DeviceDialog.Message(R.string.local_pairing_enable_notifications)
            return
        }
        try {
            LocalPairingService.start(app)
        } catch (error: RuntimeException) {
            dialog = null
            statusMessage = deviceStatus(error.localizedMessage ?: app.getString(R.string.local_discovery_unavailable))
            return
        }
        val settings = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        if (openSettings(app, settings) || openSettings(app, Intent(Settings.ACTION_SETTINGS))) {
            statusMessage = null
            dialog = null
        } else {
            stopLocalPairing()
            dialog = DeviceDialog.Message(R.string.open_developer_options_in_system_settings_and_turn)
        }
    }

    private fun consumePairingResult() {
        if (!closed && !busy && !connected && prefs.getBoolean(LocalPairingService.CONNECT_PENDING, false)) {
            connectLocal(notify = true)
        }
    }

    private fun connectLocal(notify: Boolean) {
        job(deviceStatus(R.string.connecting_to_wireless_debugging_on_this_device), { LocalWirelessAdb.connect(app, 0) }, { error ->
            if (notify) finishPairingConnection(error)
            dialog = DeviceDialog.LocalSetup
        }) { adb ->
            localTarget = true
            dialog = null
            if (notify) finishPairingConnection(app.getString(R.string.connected_tap_to_return_to_pocket_studio))
            identify(adb)
        }
    }

    private fun finishPairingConnection(message: String) {
        prefs.edit().remove(LocalPairingService.CONNECT_PENDING).apply()
        LocalPairingService.result(app, message)
    }

    fun chooseUsb() {
        stopLocalPairing()
        val devices = UsbAdb.devices(usb)
        when (devices.size) {
            0 -> statusMessage = deviceStatus(R.string.no_adb_device_found_connect_the_target_phone)
            1 -> requestUsb(devices.first())
            else -> dialog = DeviceDialog.ListPicker(R.string.select_usb_device, devices.map { "${it.productName ?: "USB"} · ${it.deviceName}" }) {
                requestUsb(devices[it])
            }
        }
    }

    private fun requestUsb(device: UsbDevice) {
        val manager = usb ?: return
        if (manager.hasPermission(device)) { connect(device); return }
        waitingUsb = device
        busy = true
        statusMessage = deviceStatus(R.string.waiting_for_usb_access)
        val request = ++generation
        val intent = Intent(permissionAction).setPackage(app.packageName).putExtra("request", request)
        try {
            manager.requestPermission(device, PendingIntent.getBroadcast(app, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } catch (e: RuntimeException) {
            waitingUsb = null
            busy = false
            statusMessage = e.localizedMessage?.let(::deviceStatus) ?: deviceStatus(R.string.unable_to_request_usb_access)
        }
    }

    private fun connect(device: UsbDevice) = job(deviceStatus(R.string.waiting_for_the_target_phone_to_authorize_usb), { UsbAdb.connect(app, device) }, ::identify)

    enum class Connection { TCP, PAIR, WIRELESS }
    fun network(mode: Connection) {
        stopLocalPairing()
        val pair = mode == Connection.PAIR
        val tcp = mode == Connection.TCP
        val key = if (tcp) "adbHost" else "wirelessHost"
        val title = when (mode) {
            Connection.PAIR -> R.string.pair_wireless_device
            Connection.WIRELESS -> R.string.connect_paired_device
            Connection.TCP -> R.string.connect_over_adb_tcp
        }
        val fields = mutableListOf(
            DeviceDialog.Field(R.string.host_or_ip_address, prefs.getString(key, "").orEmpty()),
            DeviceDialog.Field(if (pair) R.string.pairing_port else R.string.connection_port,
                if (tcp) "5555" else if (pair) "" else prefs.getString("wirelessPort", "").orEmpty(), true)
        )
        if (pair) fields += DeviceDialog.Field(R.string.six_digit_pairing_code, numeric = true, secret = true)
        dialog = DeviceDialog.Form(title, fields, if (pair) R.string.pair else R.string.connect) { values ->
            val address = values[0].trim()
            val port = values[1].toIntOrNull()
            require(address.isNotEmpty() && port != null && port in 1..65535) { app.getString(R.string.enter_a_valid_address_and_port) }
            prefs.edit().putString(key, address).apply()
            if (pair) {
                val digits = values[2]
                require(digits.matches(Regex("[0-9]{6}"))) { app.getString(R.string.enter_the_six_digit_pairing_code) }
                job(deviceStatus(R.string.pairing), { WirelessAdb.pair(app, address, port, digits) }) {
                    statusMessage = deviceStatus(R.string.paired_successfully)
                    network(Connection.WIRELESS)
                }
            } else {
                if (!tcp) prefs.edit().putString("wirelessPort", port.toString()).apply()
                job(deviceStatus(R.string.connecting_to, address), {
                    if (tcp) UsbAdb.connectTcp(app, address, port) else WirelessAdb.connect(app, address, port)
                }, ::identify)
            }
        }
    }

    private fun currentTarget(): Target? = target.also {
        if (it == null) dialog = DeviceDialog.Message(R.string.connect_to_a_target_device_first)
    }

    fun install(file: File, launchAfter: Boolean = false) {
        val adb = currentTarget() ?: return
        job(deviceStatus(R.string.installing, file.name), {
            file.inputStream().use { adb.install(it, file.length()) }
            if (launchAfter) app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
                ?: throw IOException(app.getString(R.string.apk_installed_unable_to_read_the_package_name))
            else file.name
        }) {
            statusMessage = deviceStatus(R.string.installed_with_detail, it)
            if (launchAfter) {
                try { launch(it) } catch (e: IllegalArgumentException) { statusMessage = deviceStatus(R.string.installation_succeeded, e.localizedMessage) }
            }
        }
    }

    fun install(uri: Uri) {
        val adb = currentTarget() ?: return
        job(deviceStatus(R.string.installing_apk), {
            val tmp = File.createTempFile("install-", ".apk", app.cacheDir)
            try {
                val source = app.contentResolver.openInputStream(uri) ?: throw IOException(app.getString(R.string.unable_to_read_apk))
                source.use { input -> tmp.outputStream().use(input::copyTo) }
                tmp.inputStream().use { adb.install(it, tmp.length()) }
            } finally {
                tmp.delete()
            }
        }) { statusMessage = deviceStatus(R.string.apk_installed) }
    }

    fun requestLaunch() {
        dialog = DeviceDialog.Form(R.string.launch_app, listOf(DeviceDialog.Field(R.string.app_package_name, prefs.getString("launchPkg", "").orEmpty())), R.string.launch) {
            launch(it.first().trim())
        }
    }

    private fun launch(pkg: String) {
        val adb = currentTarget() ?: return
        require(pkg.matches(Regex("[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)+"))) { app.getString(R.string.enter_a_valid_app_package_name) }
        prefs.edit().putString("launchPkg", pkg).apply()
        job(deviceStatus(R.string.launching_app), {
            val component = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg")
                .trim().lineSequence().last().trim()
            if (!component.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))) throw IOException(app.getString(R.string.launcher_activity_not_found, pkg))
            val result = adb.shell("am start -W -n '$component'")
            if (!result.contains("Status: ok") && !result.contains("brought to the front")) throw IOException(result.trim())
            result
        }) { stopLog(); output = it; statusMessage = deviceStatus(R.string.launched, pkg) }
    }

    private suspend fun queryDevice(read: suspend (Target) -> String): String = withContext(Dispatchers.Main.immediate) {
        val selected = target ?: throw IOException(app.getString(R.string.connect_to_a_target_device_first))
        val result = withTimeoutOrNull(30_000L) {
            withContext(Dispatchers.IO) { read(selected) }
        } ?: throw IOException(app.getString(R.string.agent_device_query_timeout))
        if (target !== selected || !selected.isConnected) throw IOException(app.getString(R.string.target_device_disconnected))
        result
    }

    suspend fun readLogcat(projectApk: File?, packageName: String?, maxLines: Int = 150): String = queryDevice { adb ->
        val pkg = packageName ?: projectApk?.let { app.packageManager.getPackageArchiveInfo(it.absolutePath, 0)?.packageName }
            ?: throw IOException(app.getString(R.string.agent_log_package_required))
        DeviceAgentTools.logs(adb, pkg, maxLines)
    }

    suspend fun inspectDeviceUi(): String = queryDevice { DeviceAgentTools.inspect(it) }

    fun inspect() {
        val adb = currentTarget() ?: return
        job(deviceStatus(R.string.reading_target_screen), { DeviceInspector.capture(adb) }) { dialog = DeviceDialog.Inspection(it) }
    }

    fun shell() {
        val adb = currentTarget() ?: return
        dialog = DeviceDialog.Form(R.string.target_device_shell, listOf(DeviceDialog.Field(R.string.command)), R.string.execute) {
            val command = it.first().trim()
            require(command.isNotEmpty()) { app.getString(R.string.enter_a_command) }
            job(deviceStatus(R.string.executing), { adb.shell(command) }) { result -> stopLog(); output = "$ " + command + "\n" + result }
        }
    }

    fun processes() {
        val adb = currentTarget() ?: return
        val ownPid = if (localTarget) android.os.Process.myPid().toString() else null
        job(deviceStatus(R.string.reading_debuggable_processes), {
            val pids = adb.open("track-jdwp").use {
                val input = DataInputStream(it.input())
                val length = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII).toInt(16)
                ByteArray(length).also(input::readFully).toString(Charsets.US_ASCII).lines()
                    .filter { pid -> pid != ownPid && pid.matches(Regex("[0-9]+")) }
            }
            val names = adb.shell("ps -A -o PID,NAME").lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }
                .filter { it.size == 2 }.associate { it[0] to it[1] }
            pids.map { it to (names[it] ?: "") }
        }) { list ->
            if (list.isEmpty()) statusMessage = deviceStatus(R.string.no_debuggable_processes_on_the_target_start_a)
            else dialog = DeviceDialog.ListPicker(R.string.attach_to_process, list.map { (pid, name) -> "$pid   $name" }) { index ->
                val (pid, name) = list[index]
                attach(adb, pid, name)
            }
        }
    }

    fun detach() {
        attachment++
        closeLater(debugger)
        debugger = null
        thread = 0
        process = ""
        breakpoints.clear()
    }

    private fun attach(adb: Target, pid: String, name: String) {
        detach()
        stopLog()
        output = ""
        val id = attachment
        val listener = object : JdwpSession.Listener {
            override fun onStopped(threadId: Long, reason: String) {
                main.post {
                    if (id == attachment) {
                        thread = threadId
                        statusMessage = deviceStatus(reason)
                        if (attached && !busy) stack()
                    }
                }
            }
            override fun onDisconnected(reason: String) {
                main.post {
                    if (id == attachment) {
                        generation++
                        busy = false
                        detach()
                        statusMessage = deviceStatus(reason)
                    }
                }
            }
        }
        job(deviceStatus(R.string.attaching), {
            val stream = adb.open("jdwp:$pid")
            JdwpSession.attach(stream.input(), stream.output(), stream, listener)
        }) { session ->
            debugger = session
            process = name.ifBlank { pid }
            if (paused) stack()
        }
    }

    fun breakpoint(className: String, line: Int) {
        if (!attached) { dialog = DeviceDialog.Message(R.string.attach_to_a_target_app_process_first); return }
        dialog = DeviceDialog.Form(R.string.set_line_breakpoint, listOf(DeviceDialog.Field(R.string.fully_qualified_class_name, className),
            DeviceDialog.Field(R.string.source_line, line.toString(), true)), R.string.set) {
            val cls = it[0].trim()
            val n = it[1].toIntOrNull()
            require(cls.isNotEmpty() && n != null && n > 0) { app.getString(R.string.enter_a_fully_qualified_class_name_and_a) }
            setBreakpoint(cls to n, true)
        }
    }

    fun toggleBreakpoint(className: String, line: Int) {
        if (!attached || className.isBlank()) { breakpoint(className, line); return }
        val existing = breakpoints.firstOrNull { it.second == line && (it.first == className || it.first.startsWith(className + '$')) }
        setBreakpoint(existing ?: (className to line), existing == null)
    }

    private fun setBreakpoint(point: Pair<String, Int>, add: Boolean) {
        val session = debugger ?: return
        val (cls, line) = point
        job(deviceStatus(if (add) R.string.setting_breakpoint else R.string.removing_breakpoint), {
            if (add) session.setBreakpoint(cls, line) else session.removeBreakpoint(cls, line)
        }) {
            if (add && point !in breakpoints) breakpoints.add(point)
            if (!add) breakpoints.remove(point)
            statusMessage = deviceStatus(if (add) R.string.breakpoint_set else R.string.breakpoint_removed, "$cls:$line")
        }
    }

    fun markersFor(className: String): Set<Int> =
        breakpoints.filter { it.first == className || it.first.startsWith(className + '$') }.map { it.second }.toSet()

    fun removeBreakpoint() {
        val points = breakpoints.toList()
        dialog = DeviceDialog.ListPicker(R.string.remove_breakpoint, points.map { "${it.first}:${it.second}" }) { setBreakpoint(points[it], false) }
    }

    fun classes(className: String, line: Int) {
        val session = debugger ?: return
        dialog = DeviceDialog.Form(R.string.find_loaded_classes, listOf(DeviceDialog.Field(R.string.class_name_contains, className.substringBeforeLast('.', ""))), R.string.search) {
            val filter = it.first()
            job(deviceStatus(R.string.finding_classes), { session.loadedClasses().filter { it.contains(filter) } }) { list ->
                if (list.isEmpty()) statusMessage = deviceStatus(R.string.no_matching_loaded_classes)
                else dialog = DeviceDialog.ListPicker(R.string.select_breakpoint_class, list) { breakpoint(list[it], line) }
            }
        }
    }

    fun stack() = inspectFrame(true)
    fun locals() = inspectFrame(false)
    private fun inspectFrame(stack: Boolean) {
        val session = debugger ?: return
        val stopped = thread
        if (stopped == 0L) return
        job(deviceStatus(if (stack) R.string.reading_call_stack else R.string.reading_variables), {
            if (stack) session.stack(stopped) else session.locals(stopped)
        }) { values ->
            stopLog()
            outputMessage = if (values.isEmpty()) {
                deviceStatus(if (stack) R.string.no_stack_frames else R.string.no_visible_variables_in_this_scope)
            } else deviceStatus(values.joinToString("\n"))
            statusMessage = deviceStatus(if (stack) R.string.call_stack else R.string.current_frame_variables)
        }
    }

    fun resume() = execute(false)
    fun stepOver() = execute(true)
    private fun execute(step: Boolean) {
        val session = debugger ?: return
        if (busy || !paused) return
        val stopped = thread
        val id = attachment
        thread = 0
        job(deviceStatus(if (step) R.string.stepping else R.string.resuming), {
            try {
                if (step) session.stepOver(stopped) else session.resume()
            } catch (e: Exception) {
                main.post { if (id == attachment && !paused) thread = stopped }
                throw e
            }
        }) {
            if (paused) stack() else output = ""
        }
    }

    fun filterLog() {
        dialog = DeviceDialog.Form(R.string.filter_logs, listOf(DeviceDialog.Field(R.string.tag_or_text, filter)), R.string.select_level) { values ->
            dialog = DeviceDialog.ListPicker(R.string.minimum_log_level, LogLevel.values().map { it.stringValue }) { level ->
                filter = values.first()
                priority = LogLevel.values()[level].priority
                if (logging) snapshotLogs() else startLog()
            }
        }
    }

    fun toggleLog() { if (logging) stopLog() else startLog() }

    private fun startLog() {
        val adb = currentTarget() ?: return
        stopLog()
        val token = logVersion
        synchronized(lines) { lines.clear() }
        logging = true
        output = ""
        io.execute {
            try {
                adb.open("shell:logcat -v brief -T 80").use { stream ->
                    main.post { if (token == logVersion) live = stream else closeLater(stream) }
                    val reader = stream.input().bufferedReader()
                    while (token == logVersion) {
                        val line = reader.readLine() ?: break
                        synchronized(lines) {
                            if (token == logVersion) {
                                lines.addLast(line)
                                if (lines.size > 400) lines.removeFirst()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                main.post { if (token == logVersion) statusMessage = e.localizedMessage?.let(::deviceStatus) ?: deviceStatus(R.string.log_stream_disconnected) }
            } finally {
                main.post {
                    if (token == logVersion) {
                        snapshotLogs()
                        live = null
                        logging = false
                    }
                }
            }
        }
        main.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!logging || closed) return
            snapshotLogs()
            main.postDelayed(this, 450)
        }
    }

    private fun snapshotLogs() {
        output = synchronized(lines) {
            lines.filter { line ->
                val level = if (line.getOrNull(1) == '/') LogLevel.getByLetter(if (line[0] == 'F') 'A' else line[0]) else null
                (level == null || level.priority >= priority) && line.contains(filter)
            }.joinToString("\n")
        }
    }

    private fun stopLog() {
        if (logging) snapshotLogs()
        logVersion++
        logging = false
        main.removeCallbacks(tick)
        closeLater(live)
        live = null
    }
}
