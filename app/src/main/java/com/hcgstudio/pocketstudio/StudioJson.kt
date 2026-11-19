package com.hcgstudio.pocketstudio

import org.json.JSONArray
import org.json.JSONObject

internal fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value) }
}
internal fun JSONObject.string(key: String): String =
    if (isNull(key)) "" else optString(key)
internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
