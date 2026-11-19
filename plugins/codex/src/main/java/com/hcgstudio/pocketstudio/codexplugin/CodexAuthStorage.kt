package com.hcgstudio.pocketstudio.codexplugin

import com.hcgstudio.pocketstudio.agent.*

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All callers hold CodexAuth's process-wide store lock. Neither state nor keys enter backups. */
internal class CodexAuthStorage(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "codex-accounts-v1"))
    private val alias = "pocketstudio.codex.accounts.v1"

    fun read(): JSONObject {
        val bytes = try { file.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) { return JSONObject() }
        if (bytes.size < 28) throw IOException(uiString(R.string.the_codex_credentials_file_is_incomplete))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 0, 12))
        return JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
    }

    fun write(state: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        check(cipher.iv.size == 12)
        val ciphertext = cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try {
            out.write(cipher.iv)
            out.write(ciphertext)
            file.finishWrite(out)
        } catch (error: Throwable) {
            file.failWrite(out)
            throw error
        }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException(uiString(R.string.the_system_key_is_unavailable_unable_to_decrypt))
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
}
