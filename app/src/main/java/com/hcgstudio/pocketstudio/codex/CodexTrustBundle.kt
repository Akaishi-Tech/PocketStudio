package com.hcgstudio.pocketstudio.codex

import com.hcgstudio.pocketstudio.uiString
import com.hcgstudio.pocketstudio.R

import android.util.Base64
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore

internal object CodexTrustBundle {
    /** Export Android system trust anchors for the bundled OpenSSL implementation. */
    @Synchronized fun prepare(dir: File): File {
        val result = File(dir, "android-system-ca.pem")
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val temp = Files.createTempFile(dir.toPath(), "codex-ca-", ".tmp")
        try {
            Files.newBufferedWriter(temp).use { writer ->
                store.aliases().asSequence().filter { it.startsWith("system:") }.forEach {
                    val cert = store.getCertificate(it)
                    writer.appendLine("-----BEGIN CERTIFICATE-----")
                    writer.appendLine(Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
                    writer.appendLine("-----END CERTIFICATE-----")
                }
            }
            check(Files.size(temp) > 0) { uiString(R.string.could_not_read_the_device_s_system_ca) }
            Files.move(temp, result.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return result
        } finally { Files.deleteIfExists(temp) }
    }
}
