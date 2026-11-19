package com.hcgstudio.pocketstudio

import android.content.Context
import org.json.JSONArray
import java.util.regex.Pattern

/** Language data is provided by the selected, verified environment APK. */
internal class EditorLanguage(val id: String, private val names: List<String>, private val extensions: List<String>,
                              val keywords: List<String>, val tokens: Pattern, val server: List<String>) {
    fun matches(file: String) = file in names || file.substringAfterLast('.', "").lowercase() in extensions

    companion object {
        fun load(context: Context): List<EditorLanguage> {
            val data = context.assets.open("linux-languages.json").bufferedReader().use { JSONArray(it.readText()) }
            return (0 until data.length()).map { i ->
                val lang = data.getJSONObject(i)
                fun strings(key: String): List<String> {
                    val values = lang.optJSONArray(key) ?: return emptyList()
                    return (0 until values.length()).map(values::getString)
                }
                val keywords = lang.getString("keywords").split(' ')
                val comment = lang.getString("comment")
                val string = lang.getString("strings")
                val word = keywords.joinToString("|") { Pattern.quote(it) }
                val tokens = Pattern.compile("""($comment)|($string)|\b($word)\b|(\b[A-Z][\w]*\b)|\b(\d+(?:\.\d+)?)\b""")
                EditorLanguage(lang.getString("id"), strings("files"), strings("extensions"),
                    keywords, tokens, strings("server"))
            }
        }
    }
}
