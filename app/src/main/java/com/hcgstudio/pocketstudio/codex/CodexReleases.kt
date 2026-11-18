package com.hcgstudio.pocketstudio.codex

import android.content.Context
import com.hcgstudio.pocketstudio.AppRelease
import com.hcgstudio.pocketstudio.GitHubApkReleases
import com.hcgstudio.pocketstudio.R
import com.hcgstudio.pocketstudio.releaseFileDigest
import com.hcgstudio.pocketstudio.uiString
import java.io.File
import java.io.IOException

internal typealias CodexRelease = AppRelease

/** The fixed release tag is an update channel; the asset digest identifies the exact download. */
internal class CodexReleases(context: Context) {
    val source = GitHubApkReleases(context, "codex-github-release", "tags/$TAG", FILE_NAME, LABEL) { TAG }

    fun latest(): CodexRelease = source.latest() ?: throw IOException(uiString(R.string.codex_release_not_published))
    fun cached(): CodexRelease? = source.cached()
    internal fun parse(body: String): CodexRelease = source.parse(body)

    companion object {
        private const val TAG = "codex-runtime"
        internal const val FILE_NAME = "PocketStudio-Codex.apk"
        private val LABEL = Regex("""Codex ([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9]+)?) \(([0-9]+)\)""")
        fun verificationError() = IOException(uiString(R.string.codex_download_verification_failed_try_again))
        fun digest(file: File): String = releaseFileDigest(file)
    }
}
