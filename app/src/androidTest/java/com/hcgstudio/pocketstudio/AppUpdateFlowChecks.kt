package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object AppUpdateFlowChecks {
    private class Source(var release: AppRelease?) : AppUpdateSource {
        val checks = AtomicInteger()
        val downloads = AtomicInteger()
        var failure: IOException? = null
        var beforeCheck: () -> Unit = {}
        var beforeProgress: () -> Unit = {}

        override fun latest(): AppRelease? {
            checks.incrementAndGet()
            beforeCheck()
            failure?.let { throw it }
            return release
        }

        override fun prepareInstaller(release: AppRelease, progress: (Long, Long) -> Unit): Intent {
            check(release == this.release)
            downloads.incrementAndGet()
            beforeProgress()
            progress(release.size, release.size)
            return Intent(Intent.ACTION_VIEW)
        }
    }

    private suspend fun CoroutineScope.finishRequests() = withTimeout(5_000L) {
        val parent = this@finishRequests.coroutineContext[Job]!!
        while (true) {
            val requests = parent.children.toList()
            if (requests.isEmpty()) break
            requests.joinAll()
        }
    }

    @JvmStatic fun run(context: Context) = runBlocking {
        val name = "app-update-flow-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val installed = AppRelease(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), 1024L, "0".repeat(64))
        val newer = installed.copy(versionCode = installed.versionCode + 1)
        val source = Source(installed)
        try {
            withContext(Dispatchers.Main) {
                val first = AppUpdates(scope, prefs, source) { false }
                first.check(automatic = true)
                scope.finishRequests()
                check(source.checks.get() == 1 && source.downloads.get() == 0 && !first.visible)

                val metered = AppUpdates(scope, prefs, source) { true }
                metered.check(automatic = true)
                scope.finishRequests()
                check(source.checks.get() == 1 && !metered.visible) { "Automatic check was not throttled across controllers" }
                metered.check(automatic = false)
                scope.finishRequests()
                check(source.checks.get() == 2 && metered.visible && metered.state is AppUpdateState.Current)
                metered.dismiss()

                source.release = newer
                metered.check(automatic = false)
                scope.finishRequests()
                check(metered.visible && metered.state == AppUpdateState.Available(newer) && source.downloads.get() == 0)
                metered.dismiss()

                source.release = null
                metered.check(automatic = false)
                scope.finishRequests()
                check(metered.visible && metered.state is AppUpdateState.Unpublished)
                metered.dismiss()
                source.failure = IOException("release service unavailable")
                metered.check(automatic = false)
                scope.finishRequests()
                check(metered.visible && metered.state is AppUpdateState.Failed)
                metered.dismiss()
                source.failure = null

                val now = System.currentTimeMillis()
                prefs.edit().putLong("lastCheck", now - TimeUnit.HOURS.toMillis(23)).apply()
                val checks = source.checks.get()
                metered.check(automatic = true)
                scope.finishRequests()
                check(source.checks.get() == checks)
                prefs.edit().putLong("lastCheck", now - TimeUnit.HOURS.toMillis(25)).apply()
                source.release = newer
                metered.check(automatic = true)
                scope.finishRequests()
                check(source.checks.get() == checks + 1 && metered.visible && metered.state == AppUpdateState.Available(newer))
                check(source.downloads.get() == 0)
                metered.download(newer)
                scope.finishRequests()
                check(metered.visible && metered.state is AppUpdateState.Ready && source.downloads.get() == 1)

                prefs.edit().clear().apply()
                val unmetered = AppUpdates(scope, prefs, source) { false }
                unmetered.check(automatic = true)
                scope.finishRequests()
                check(unmetered.visible && unmetered.state is AppUpdateState.Ready && source.downloads.get() == 2)

                prefs.edit().clear().apply()
                val connectionMetered = AtomicBoolean(false)
                val changingNetwork = AppUpdates(scope, prefs, source, connectionMetered::get)
                val entered = CountDownLatch(1)
                val resume = CountDownLatch(1)
                source.beforeCheck = {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                source.beforeProgress = { connectionMetered.set(true) }
                val previousChecks = source.checks.get()
                changingNetwork.check(automatic = true)
                try {
                    withContext(Dispatchers.IO) { check(entered.await(3, TimeUnit.SECONDS)) }
                    changingNetwork.check(automatic = false)
                    check(changingNetwork.visible && changingNetwork.state == AppUpdateState.Checking)
                } finally {
                    resume.countDown()
                }
                scope.finishRequests()
                check(source.checks.get() == previousChecks + 1) { "Manual check duplicated the in-flight request" }
                check(source.downloads.get() == 3 && changingNetwork.state == AppUpdateState.Available(newer)) {
                    "Automatic download continued after the network became metered"
                }
            }
        } finally {
            scope.cancel()
            context.deleteSharedPreferences(name)
        }
    }
}
