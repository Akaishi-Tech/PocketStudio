package com.hcgstudio.pocketstudio.wireless

import com.hcgstudio.pocketstudio.uiString
import com.hcgstudio.pocketstudio.R

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Uses the system's wireless debugging services on this phone. Call from a worker thread. */
object LocalWirelessAdb {
    private const val PAIRING_TYPE = "_adb-tls-pairing._tcp"

    @JvmStatic
    fun pair(context: Context, code: String, pairingPort: Int) {
        require(code.matches(Regex("[0-9]{6}"))) { uiString(R.string.enter_the_six_digit_pairing_code_from_this) }
        val port = localPort(context, pairingPort, PAIRING_TYPE, uiString(R.string.pairing_service))
        WirelessAdb.pair(context, "127.0.0.1", port, code)
    }

    @JvmStatic
    fun connect(context: Context, connectPort: Int): WirelessAdb {
        val port = localPort(context, connectPort, "_adb-tls-connect._tcp", uiString(R.string.connection_service))
        try {
            return WirelessAdb.connect(context, "127.0.0.1", port)
        } catch (_: IOException) {
            // adbd can advertise its port before TLS is ready, including just after disconnect.
            Thread.sleep(750)
        }
        return WirelessAdb.connect(context, "127.0.0.1", port)
    }

    private fun localPort(context: Context, port: Int, type: String, label: String): Int {
        if (Build.VERSION.SDK_INT < 30) throw IOException(uiString(R.string.local_wireless_debugging_requires_android_11_or_later))
        require(port in 0..65535) { uiString(R.string.enter_a_valid_port, label) }
        if (port != 0) return port

        return discover(context, type, label, 12_000) { it != 0 }
    }

    /** Report port changes while the system pairing dialog opens, closes or generates a new code. */
    internal fun watchPairing(context: Context, onPortChanged: (Int) -> Unit) {
        discover(context, PAIRING_TYPE, uiString(R.string.pairing_service), Long.MAX_VALUE) { port ->
            onPortChanged(port)
            false
        }
    }

    private data class ServiceEvent(val info: NsdServiceInfo, val lost: Boolean = false)

    private fun discover(context: Context, type: String, label: String, timeout: Long, onPort: (Int) -> Boolean): Int {
        val nsd = context.getSystemService(NsdManager::class.java)
            ?: throw IOException(uiString(R.string.local_discovery_unavailable))
        val found = LinkedBlockingQueue<Result<ServiceEvent>>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { found.offer(Result.success(ServiceEvent(info))) }
            override fun onServiceLost(info: NsdServiceInfo) { found.offer(Result.success(ServiceEvent(info, lost = true))) }
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                found.offer(Result.failure(IOException(uiString(R.string.local_discovery_failed, error))))
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
        }
        val deadline = if (timeout == Long.MAX_VALUE) timeout else SystemClock.elapsedRealtime() + timeout
        var localName: String? = null
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            // Android 11 allows one active NSD resolution. Resolve found services in order.
            while (SystemClock.elapsedRealtime() < deadline) {
                val event = found.poll(deadline - SystemClock.elapsedRealtime(), TimeUnit.MILLISECONDS)?.getOrThrow() ?: break
                if (event.lost) {
                    if (event.info.serviceName == localName) {
                        localName = null
                        onPort(0)
                    }
                    continue
                }
                val resolved = resolve(nsd, event.info, deadline) ?: continue
                if (resolved.port in 1..65535 && isLocalService(resolved)) {
                    localName = event.info.serviceName
                    if (onPort(resolved.port)) return resolved.port
                }
            }
        } catch (_: TimeoutException) {
            // Report the same recovery steps if service resolution stalls.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(uiString(R.string.local_service_discovery_stopped), e)
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { /* already stopped */ }
        }
        throw IOException(uiString(R.string.local_service_not_found, label))
    }

    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo, deadline: Long): NsdServiceInfo? {
        val result = CompletableFuture<NsdServiceInfo?>()
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) { result.complete(resolved) }
            override fun onResolveFailed(service: NsdServiceInfo, error: Int) { result.complete(null) }
        })
        return result.get(deadline - SystemClock.elapsedRealtime(), TimeUnit.MILLISECONDS)
    }

    @Suppress("DEPRECATION")
    private fun isLocalService(service: NsdServiceInfo): Boolean {
        // Use the advertised address only to identify this phone. Its adbd is reachable on loopback.
        val addresses = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses else listOfNotNull(service.host)
        return addresses.any { NetworkInterface.getByInetAddress(it) != null }
    }
}
