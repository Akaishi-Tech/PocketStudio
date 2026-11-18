package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.util.concurrent.TimeUnit

internal interface AppUpdateSource {
    fun latest(): AppRelease?
    fun prepareInstaller(release: AppRelease, progress: (Long, Long) -> Unit): Intent
}

internal sealed interface AppUpdateState {
    data object Checking : AppUpdateState
    data object Current : AppUpdateState
    data object Unpublished : AppUpdateState
    data class Available(val release: AppRelease) : AppUpdateState
    class Downloading(val release: AppRelease) : AppUpdateState {
        var percent by mutableIntStateOf(0)
    }
    data class Ready(val release: AppRelease, val intent: Intent) : AppUpdateState
    data class Failed(val message: String) : AppUpdateState
}

internal class AppUpdates(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val releases: AppUpdateSource,
    private val isMetered: () -> Boolean
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope, context.getSharedPreferences("app-update-schedule", Context.MODE_PRIVATE), AppReleases(context),
        { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }
    )

    var state by mutableStateOf<AppUpdateState?>(null)
        private set
    var visible by mutableStateOf(false)
        private set
    private var work: Job? = null
    private var automaticWork = false

    fun check(automatic: Boolean) {
        if (work?.isActive == true) {
            if (!automatic) visible = true
            return
        }
        val now = System.currentTimeMillis()
        val elapsed = now - prefs.getLong("lastCheck", 0L)
        if (automatic && (visible || elapsed in 0 until TimeUnit.DAYS.toMillis(1))) return
        prefs.edit { putLong("lastCheck", now) }
        automaticWork = automatic
        visible = !automatic
        state = AppUpdateState.Checking
        work = scope.launch {
            val release = try {
                runInterruptible(Dispatchers.IO) { releases.latest() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                state = AppUpdateState.Failed(uiString(R.string.update_check_failed_format, e.localizedMessage ?: e.javaClass.simpleName))
                return@launch
            }
            when {
                release == null -> state = AppUpdateState.Unpublished
                release.versionCode <= BuildConfig.VERSION_CODE -> state = AppUpdateState.Current
                automatic && !isMetered() -> prepare(release, automatic = true)
                else -> offer(release)
            }
        }
    }

    fun download(release: AppRelease) {
        if (work?.isActive == true) return
        automaticWork = false
        visible = true
        work = scope.launch { prepare(release, automatic = false) }
    }

    private fun offer(release: AppRelease) {
        state = AppUpdateState.Available(release)
        visible = true
    }

    private suspend fun prepare(release: AppRelease, automatic: Boolean) {
        val download = AppUpdateState.Downloading(release)
        state = download
        try {
            val intent = runInterruptible(Dispatchers.IO) {
                releases.prepareInstaller(release) { bytes, total ->
                    if (automatic && isMetered()) throw MeteredNetwork()
                    download.percent = (bytes * 100 / total).toInt()
                }
            }
            state = AppUpdateState.Ready(release, intent)
            visible = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: MeteredNetwork) {
            currentCoroutineContext().ensureActive()
            offer(release)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            state = AppUpdateState.Failed(uiString(R.string.app_update_download_failed, e.localizedMessage ?: e.javaClass.simpleName))
        }
    }

    fun disableAutomatic() {
        if (automaticWork) dismiss()
    }

    fun dismiss() {
        work?.cancel()
        work = null
        state = null
        visible = false
        automaticWork = false
    }

    fun installerFailed(message: String) {
        state = AppUpdateState.Failed(message)
        visible = true
    }

    private class MeteredNetwork : IOException()
}
