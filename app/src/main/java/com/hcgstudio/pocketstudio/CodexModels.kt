package com.hcgstudio.pocketstudio

import org.json.JSONArray
import org.json.JSONObject

internal data class CodexModel(
    val id: String,
    val displayName: String,
    val efforts: List<String>,
    val defaultEffort: String?,
    val supportsFast: Boolean,
    val isDefault: Boolean = false
) {
    companion object {
        fun fromRuntime(row: JSONObject): CodexModel? {
            if (row.optBoolean("hidden")) return null
            val id = row.string("model").ifBlank { row.string("id") }
            if (id.isBlank()) return null
            val efforts = row.optJSONArray("supportedReasoningEfforts").efforts("reasoningEffort")
            return CodexModel(id, row.string("displayName").ifBlank { id }, efforts,
                row.string("defaultReasoningEffort").takeIf { it in efforts },
                supportsFast(row.optJSONArray("serviceTiers"), row.optJSONArray("additionalSpeedTiers")),
                row.optBoolean("isDefault"))
        }

        fun fromPlan(row: JSONObject, runtimeFastModels: Set<String>): CodexModel? {
            if (row.string("visibility") != "list") return null
            val id = row.string("slug")
            if (id.isBlank()) return null
            val efforts = row.optJSONArray("supported_reasoning_levels").efforts("effort")
            return CodexModel(id, row.string("display_name").ifBlank { id }, efforts,
                row.string("default_reasoning_level").takeIf { it in efforts },
                id in runtimeFastModels && supportsFast(row.optJSONArray("service_tiers"), row.optJSONArray("additional_speed_tiers")))
        }

        private fun JSONArray?.efforts(field: String): List<String> =
            objects().map { it.string(field) }.filter { it.isNotBlank() }.distinct()

        private fun supportsFast(tiers: JSONArray?, legacyTiers: JSONArray?): Boolean {
            if (tiers != null) return tiers.objects().any { it.string("id") == "priority" }
            return legacyTiers != null && (0 until legacyTiers.length()).any { legacyTiers.optString(it) == "fast" }
        }
    }
}
