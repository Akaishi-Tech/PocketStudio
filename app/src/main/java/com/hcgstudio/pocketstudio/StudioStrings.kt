@file:JvmName("StudioStrings")

package com.hcgstudio.pocketstudio

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes

private lateinit var studioApplication: Application

class PocketStudioApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        studioApplication = this
    }
}

/** Read the current app locale for diagnostics produced by context-free project tools. */
internal fun uiString(@StringRes id: Int): String = studioApplication.getString(id)

internal fun uiString(@StringRes id: Int, vararg arguments: Any?): String =
    studioApplication.getString(id, *arguments)
