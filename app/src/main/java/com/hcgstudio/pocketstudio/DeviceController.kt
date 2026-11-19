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
import com.hcgstudio.pocketstudio.plugin.RemoteConnection
import com.hcgstudio.pocketstudio.adb.Target
import com.hcgstudio.pocketstudio.adb.UsbAdb
import com.hcgstudio.pocketstudio.debug.JdwpSession
import com.android.ddmlib.Log.LogLevel
import com.hcgstudio.pocketstudio.wireless.LocalWirelessAdb
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
    data class Network(val mode: RemoteConnection) : DeviceDialog
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
    private var attachment = java.util.Random().nextInt()
    private var closed = false
    private var target by mutableStateOf<Target?>(null)
    private var debugger by mutableStateOf<JdwpSession?>(null)
    private var waitingUsb: UsbDevice? = null
    private var localTarget = false
    private var remoteConnection = false
    private val pairingResult = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LocalPairingService.CONNECT_PENDING) consumePairingResult()
    }
    private var thread by mutableLongStateOf(0)
    private val breakpointWorker = Executors.newSingleThreadExecutor()
    private var breakpointKey: String? = null
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

    private val debugCommands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra("session", attachment.inv()) != attachment || !attached) return
            when (intent.getStringExtra("command")) {
                "resume" -> resume()
                "step" -> stepOver()
                "detach" -> detach()
            }
        }
    }

    private fun notifyDebugger() {
        if (attached) DebugNotifications.update(app, process, attachment, paused, true)
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
        androidx.core.content.ContextCompat.registerReceiver(app, debugCommands, IntentFilter(DEBUG_COMMAND),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
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

    fun closeRemoteConnection() {
        if (remoteConnection && !localTarget) disconnect()
    }

    fun disconnect() {
        generation++
        activeJob?.cancel(true)
        activeJob = null
        busy = false
        waitingUsb = null
        remoteConnection = false
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
        app.unregisterReceiver(debugCommands)
        breakpointWorker.shutdownNow()
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
        remoteConnection = false
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
        val devices = try { AndroidEnvironments.require(app).usbDevices() }
            catch (e: Exception) {
                statusMessage = deviceStatus(e.localizedMessage ?: app.getString(R.string.android_environment_required))
                return
            }
        remoteConnection = true
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

    private fun connect(device: UsbDevice) = job(deviceStatus(R.string.waiting_for_the_target_phone_to_authorize_usb), { AndroidEnvironments.require(app).connectUsb(device) }, ::identify)

    fun network(mode: RemoteConnection) {
        if (busy || connected) return
        remoteConnection = true
        stopLocalPairing()
        dialog = DeviceDialog.Network(mode)
    }

    fun connectRemote(mode: RemoteConnection, address: String, port: Int, code: String = "") {
        if (busy || connected) return
        remoteConnection = true
        val tcp = mode == RemoteConnection.TCP
        if (mode == RemoteConnection.PAIR) {
            job(deviceStatus(R.string.pairing), { AndroidEnvironments.require(app).pairRemote(address, port, code) }) {
                statusMessage = deviceStatus(R.string.paired_successfully)
                network(RemoteConnection.WIRELESS)
            }
        } else {
            job(deviceStatus(R.string.connecting_to, address), {
                AndroidEnvironments.require(app).connectRemote(address, port, tcp)
            }) { adb ->
                localTarget = address.equals("localhost", ignoreCase = true) ||
                    (android.util.Patterns.IP_ADDRESS.matcher(address.removeSurrounding("[", "]")).matches() &&
                        runCatching {
                            val ip = java.net.InetAddress.getByName(address.removeSurrounding("[", "]"))
                            ip.isLoopbackAddress || java.net.NetworkInterface.getByInetAddress(ip) != null
                        }.getOrDefault(false))
                if (localTarget && tcp) prefs.edit().putInt("localAdbTcpPort", port).apply()
                identify(adb)
            }
        }
    }

    /** Install only on this phone, even when the workbench is debugging a different device. */
    suspend fun installPluginLocally(file: File): Boolean {
        val connectedLocal = target?.takeIf { localTarget && it.isConnected }
        return kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            fun install(adb: Target) = file.inputStream().use { adb.install(it, file.length()) }
            if (connectedLocal != null) {
                install(connectedLocal)
                return@runInterruptible true
            }
            fun tcp(): Target? = try {
                UsbAdb.connectTcp(app, "127.0.0.1", prefs.getInt("localAdbTcpPort", 5555))
            } catch (_: IOException) { null }
            fun wireless(): Target? = try { LocalWirelessAdb.connect(app, 0) }
                catch (_: IOException) { null }
                catch (_: SecurityException) { null }
            val local = if (prefs.getBoolean("localAdbPaired", false)) wireless() ?: tcp() else tcp() ?: wireless()
            if (local == null) return@runInterruptible false
            local.use { install(it) }
            true
        }
    }

    private fun currentTarget(): Target? = target.also {
        if (it == null) dialog = DeviceDialog.Message(R.string.connect_to_a_target_device_first)
    }

    fun install(file: File, launchAfter: Boolean = false, debug: Boolean = false) {
        val adb = currentTarget() ?: return
        val projectAtInstall = breakpointKey
        job(deviceStatus(R.string.installing, file.name), {
            file.inputStream().use { adb.install(it, file.length()) }
            if (launchAfter) app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
                ?: throw IOException(app.getString(R.string.apk_installed_unable_to_read_the_package_name))
            else file.name
        }) {
            statusMessage = deviceStatus(R.string.installed_with_detail, it)
            if (launchAfter && (!debug || breakpointKey == projectAtInstall)) {
                try { launch(it, if (debug) file else null) } catch (e: IllegalArgumentException) { statusMessage = deviceStatus(R.string.installation_succeeded, e.localizedMessage) }
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

    private fun launch(pkg: String, debugApk: File? = null) {
        val adb = currentTarget() ?: return
        require(pkg.matches(Regex("[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)+"))) { app.getString(R.string.enter_a_valid_app_package_name) }
        prefs.edit().putString("launchPkg", pkg).apply()
        if (debugApk != null) {
            detach()
            DebugNotifications.update(app, pkg, attachment, false, false)
        }
        val launchAttachment = attachment
        job(deviceStatus(R.string.launching_app), {
            val component = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg")
                .trim().lineSequence().last().trim()
            if (!component.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))) throw IOException(app.getString(R.string.launcher_activity_not_found, pkg))
            if (debugApk != null) {
                val info = app.packageManager.getPackageArchiveInfo(debugApk.absolutePath, android.content.pm.PackageManager.GET_ACTIVITIES)
                val activity = component.substringAfter('/').let { if (it.startsWith('.')) pkg + it else it }
                val processName = info?.activities?.find { it.name == activity }?.processName ?: pkg
                require(processName.matches(Regex("[A-Za-z0-9_.:]+")))
                val started = adb.shell("am start -S -D -n '$component'")
                if (started.contains("Error", ignoreCase = true) || started.contains("Exception")) throw IOException(started.trim())
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000L
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val pid = adb.shell("pidof '$processName'").trim().split(' ').first()
                    if (pid.matches(Regex("[0-9]+"))) return@job pid
                    Thread.sleep(120)
                }
                throw IOException(app.getString(R.string.debug_process_missing))
            }
            val result = adb.shell("am start -W -n '$component'")
            if (!result.contains("Status: ok") && !result.contains("brought to the front")) throw IOException(result.trim())
            result
        }, { if (debugApk != null) DebugNotifications.notice.clear(app) }) {
            if (debugApk != null) {
                if (attachment == launchAttachment) attach(adb, it, pkg, starting = true)
            } else { stopLog(); output = it; statusMessage = deviceStatus(R.string.launched, pkg) }
        }
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
        DebugNotifications.notice.clear(app)
    }

    private fun attach(adb: Target, pid: String, name: String, starting: Boolean = false) {
        if (!starting) {
            detach()
            DebugNotifications.update(app, name.ifBlank { pid }, attachment, false, false)
        }
        stopLog()
        output = ""
        val id = attachment
        val listener = object : JdwpSession.Listener {
            override fun onStopped(threadId: Long, reason: String) {
                main.post {
                    if (id == attachment) {
                        thread = threadId
                        statusMessage = deviceStatus(reason)
                        notifyDebugger()
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
        val initial = breakpoints.toSet()
        job(deviceStatus(R.string.attaching), {
            val stream = adb.open("jdwp:$pid")
            val session = JdwpSession.attach(stream.input(), stream.output(), stream, listener)
            try {
                for ((cls, line) in initial) session.setBreakpoint(cls, line)
            } catch (failure: Exception) {
                session.close()
                throw failure
            }
            session
        }, { DebugNotifications.notice.clear(app) }) { session ->
            if (id != attachment) { closeLater(session); return@job }
            debugger = session
            process = name.ifBlank { pid }
            notifyDebugger()
            for (removed in initial - breakpoints.toSet()) bindBreakpoint(session, removed, false)
            for (added in breakpoints - initial) bindBreakpoint(session, added, true)
            if (paused) stack()
        }
    }

    fun selectBreakpointProject(project: String?) {
        if (breakpointKey == project) return
        detach()
        breakpointKey = project
        breakpoints.clear()
        project?.let {
            for (entry in prefs.getStringSet("breakpoints:$it", emptySet()).orEmpty()) {
                val line = entry.substringAfterLast(':').toIntOrNull() ?: continue
                breakpoints.add(entry.substringBeforeLast(':') to line)
            }
        }
    }

    fun breakpoint(className: String, line: Int) {
        dialog = DeviceDialog.Form(R.string.set_line_breakpoint, listOf(DeviceDialog.Field(R.string.fully_qualified_class_name, className),
            DeviceDialog.Field(R.string.source_line, line.toString(), true)), R.string.set) {
            val cls = it[0].trim()
            val n = it[1].toIntOrNull()
            require(cls.isNotEmpty() && cls.none { it in "*/;:[" || it.isWhitespace() } && n != null && n > 0) { app.getString(R.string.enter_a_fully_qualified_class_name_and_a) }
            setBreakpoint(cls to n, true)
        }
    }

    fun toggleBreakpoint(className: String, line: Int) {
        if (className.isBlank()) { breakpoint(className, line); return }
        val existing = breakpoints.firstOrNull { it.second == line && (it.first == className || it.first.startsWith(className + '$')) }
        setBreakpoint(existing ?: (className to line), existing == null)
    }

    private fun setBreakpoint(point: Pair<String, Int>, add: Boolean) {
        if (add && point !in breakpoints) breakpoints.add(point)
        if (!add) breakpoints.remove(point)
        breakpointKey?.let { key ->
            prefs.edit().putStringSet("breakpoints:$key", breakpoints.map { "${it.first}:${it.second}" }.toSet()).apply()
        }
        statusMessage = deviceStatus(if (add) R.string.breakpoint_set else R.string.breakpoint_removed, "${point.first}:${point.second}")
        debugger?.let { bindBreakpoint(it, point, add) }
    }

    private fun bindBreakpoint(session: JdwpSession, point: Pair<String, Int>, add: Boolean) {
        breakpointWorker.execute {
            try {
                if (add) session.setBreakpoint(point.first, point.second) else session.removeBreakpoint(point.first, point.second)
            } catch (e: IOException) {
                main.post { if (debugger === session) statusMessage = deviceStatus(e.localizedMessage.orEmpty()) }
            }
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
        notifyDebugger()
        job(deviceStatus(if (step) R.string.stepping else R.string.resuming), {
            try {
                if (step) session.stepOver(stopped) else session.resume()
            } catch (e: Exception) {
                main.post { if (id == attachment && !paused) { thread = stopped; notifyDebugger() } }
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
