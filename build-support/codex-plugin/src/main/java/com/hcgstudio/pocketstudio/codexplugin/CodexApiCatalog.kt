package com.hcgstudio.pocketstudio.codexplugin

import com.hcgstudio.pocketstudio.agent.*

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal fun apiCatalog(baseUrl: String, bearer: String): JSONObject {
    val endpoint = URL("$baseUrl/models")
    require(endpoint.protocol == "https" || (endpoint.protocol == "http" && endpoint.host == "127.0.0.1"))
    val connection = endpoint.openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $bearer")
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
            val reason = if (code.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
                uiString(R.string.openai_request_failed_http_format, status, code)
            else uiString(R.string.openai_request_failed_http, status)
            throw IOException(reason)
        }
        return if (body.isBlank()) JSONObject() else try { JSONObject(body) }
            catch (_: JSONException) { throw IOException(uiString(R.string.openai_returned_unreadable_data)) }
    } finally { connection.disconnect() }
}
