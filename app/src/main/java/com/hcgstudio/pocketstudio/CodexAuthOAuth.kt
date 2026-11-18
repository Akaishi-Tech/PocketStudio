package com.hcgstudio.pocketstudio

import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

internal class CodexAuthHttpError(val status: Int, val code: String) :
    IOException(if (code.isEmpty()) uiString(R.string.openai_request_failed_http, status) else uiString(R.string.openai_request_failed_http_format, status, code))

internal object CodexAuthOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    private val random = SecureRandom()

    fun secret(): String = base64(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String): String =
        base64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    private fun base64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun json(url: String, form: Map<String, String>? = null, bearer: String? = null): JSONObject {
        val endpoint = URL(url)
        require(endpoint.protocol == "https" || (endpoint.protocol == "http" && endpoint.host == "127.0.0.1"))
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val bytes = form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
                    .toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val success = status in 200..299
            val stream = if (success) connection.inputStream else connection.errorStream
            val body = stream?.use { it.bufferedReader(Charsets.UTF_8).readText() }.orEmpty()
            if (!success) {
                val error = runCatching { JSONObject(body).opt("error") }.getOrNull()
                val code = when (error) {
                    is JSONObject -> error.optString("code")
                    is String -> error
                    else -> ""
                }
                throw CodexAuthHttpError(status, code.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) }.orEmpty())
            }
            return if (body.isBlank()) JSONObject() else try { JSONObject(body) }
                catch (_: JSONException) { throw IOException(uiString(R.string.openai_returned_unreadable_data)) }
        } finally { connection.disconnect() }
    }

    fun verifyIdToken(token: String, client: String, nonce: String): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3) { uiString(R.string.malformed_identity_token) }
        fun decode(input: String) = Base64.getUrlDecoder().decode(input)
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        require(header.getString("alg") == "RS256" && !header.has("crit")) { uiString(R.string.unsupported_identity_signature_algorithm) }
        val kid = header.getString("kid")
        val keys = json("$ISSUER/.well-known/jwks.json").getJSONArray("keys")
        val key = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull {
            it.optString("kid") == kid && it.optString("kty") == "RSA" &&
                it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
        } ?: throw IOException(uiString(R.string.unrecognized_openai_signing_key))
        val modulus = BigInteger(1, decode(key.getString("n")))
        val exponent = BigInteger(1, decode(key.getString("e")))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val valid = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(decode(parts[2]))
        }
        require(valid) { uiString(R.string.openai_identity_signature_verification_failed) }
        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        val aud = claims.get("aud")
        val audience = when (aud) {
            is JSONArray -> (0 until aud.length()).map { aud.getString(it) }
            is String -> listOf(aud)
            else -> emptyList()
        }
        val now = System.currentTimeMillis() / 1000
        require(claims.getString("iss") == ISSUER && client in audience &&
            (!claims.has("azp") || claims.getString("azp") == client) &&
            (audience.size == 1 || claims.optString("azp") == client) &&
            claims.getLong("exp") > now - 5 && claims.getLong("iat") <= now + 5 &&
            claims.optLong("nbf", 0) <= now + 5 && claims.getString("nonce") == nonce &&
            claims.getString("sub").isNotBlank()) { uiString(R.string.the_account_identity_expiration_or_issuer_does_not) }
        return claims
    }

    fun receiveCallback(server: ServerSocket, state: String, timeoutMs: Long): Uri {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw IOException(uiString(R.string.sign_in_timed_out_try_again))
            server.soTimeout = remaining.toInt()
            val socket = try { server.accept() }
                catch (_: SocketTimeoutException) { throw IOException(uiString(R.string.sign_in_timed_out_try_again)) }
            socket.use {
                socket.soTimeout = minOf(2000L, remaining).toInt()
                val input = socket.getInputStream()
                val head = ByteArrayOutputStream()
                var last = 0
                try {
                    while (head.size() < 16384 && SystemClock.elapsedRealtime() < deadline) {
                        val next = input.read()
                        if (next < 0) break
                        head.write(next)
                        last = (last shl 8) or next
                        if (last == 0x0d0a0d0a) break
                    }
                } catch (_: IOException) { return@use }
                val first = head.toString("US-ASCII").substringBefore("\r\n").split(' ')
                val path = first.getOrNull(1).orEmpty()
                val callback = if (last == 0x0d0a0d0a && first.first() == "GET" && path.startsWith("/auth/callback?"))
                    Uri.parse("http://127.0.0.1:${server.localPort}$path") else null
                val acceptable = callback != null && callback.getQueryParameters("state") == listOf(state)
                val body = uiString(if (acceptable) R.string.oauth_return_to_app else R.string.oauth_invalid_callback)
                    .toByteArray(Charsets.UTF_8)
                val status = if (acceptable) "200 OK" else "400 Bad Request"
                val headers = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                runCatching {
                    val response = socket.getOutputStream()
                    response.write(headers.toByteArray(Charsets.US_ASCII))
                    response.write(body)
                }
                if (acceptable) return callback!!
            }
        }
    }
}
