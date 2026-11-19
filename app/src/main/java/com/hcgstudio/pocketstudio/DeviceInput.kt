package com.hcgstudio.pocketstudio

import com.hcgstudio.pocketstudio.agent.AgentTool
import org.json.JSONArray
import org.json.JSONObject

private val coordinates = 0..65535
private val durations = 1..10000
private const val MAX_TEXT = 256
private val keys = listOf("BACK", "HOME", "APP_SWITCH", "ENTER", "TAB", "DEL", "FORWARD_DEL",
    "ESCAPE", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "SPACE")
private val fields = mapOf(
    "tap" to setOf("x", "y"),
    "swipe" to setOf("x", "y", "endX", "endY", "durationMs"),
    "long_press" to setOf("x", "y", "durationMs"),
    "text" to setOf("text"),
    "key" to setOf("key"))

private fun integer(range: IntRange) =
    json("type" to "integer", "minimum" to range.first, "maximum" to range.last)

internal fun deviceTextSchema() =
    json("type" to "string", "minLength" to 1, "maxLength" to MAX_TEXT, "pattern" to "^[ -~]+$")

internal val deviceActionTool = AgentTool("perform_device_action",
    "Send a key or type into the focused field. For a specific control, prefer its layout ref. " +
        "Use coordinate gestures as a fallback when the layout cannot identify or operate the target. " +
        "Scale screenshot coordinates to screenWidth and screenHeight. " +
        "tap and long_press require x,y; swipe requires x,y,endX,endY. Optional durationMs defaults " +
        "to 600 for long_press and 300 for swipe. text enters printable ASCII in the focused field; " +
        "key sends an Android navigation or editing key. Observe the screen again after acting.",
    json("type" to "object", "required" to JSONArray(listOf("action")), "additionalProperties" to false,
        "properties" to json(
            "action" to json("type" to "string", "enum" to JSONArray(fields.keys.toList())),
            "x" to integer(coordinates), "y" to integer(coordinates),
            "endX" to integer(coordinates), "endY" to integer(coordinates),
            "durationMs" to integer(durations),
            "text" to deviceTextSchema(),
            "key" to json("type" to "string", "enum" to JSONArray(keys)))))

internal fun deviceInputCommand(args: JSONObject): String {
    fun valid(test: Boolean, field: String) = require(test) { uiString(R.string.agent_device_input_invalid, field) }
    fun number(name: String, range: IntRange): Int {
        // JSONObject's getInt also accepts numeric strings and silently truncates fractions.
        val value = (args.opt(name) as? Number)?.toDouble()
        valid(value != null && value == value.toInt().toDouble() && value.toInt() in range, name)
        return value!!.toInt()
    }
    fun duration(default: Int) = if (args.has("durationMs")) number("durationMs", durations) else default
    val action = args.opt("action") as? String
    val allowed = fields[action] ?: throw IllegalArgumentException(uiString(R.string.agent_device_input_invalid, "action"))
    for (name in args.keys()) valid(name == "action" || name in allowed, name)
    return when (action) {
        "tap" -> "input tap ${number("x", coordinates)} ${number("y", coordinates)}"
        "long_press" -> {
            val x = number("x", coordinates)
            val y = number("y", coordinates)
            "input swipe $x $y $x $y ${duration(600)}"
        }
        "swipe" -> "input swipe ${number("x", coordinates)} ${number("y", coordinates)} " +
            "${number("endX", coordinates)} ${number("endY", coordinates)} ${duration(300)}"
        "key" -> {
            val key = args.opt("key") as? String
            valid(key in keys, "key")
            "input keyevent KEYCODE_$key"
        }
        else -> {
            val value = args.opt("text") as? String
            valid(value != null && value.length in 1..MAX_TEXT && value.all { it in ' '..'~' }, "text")
            // Android input text replaces literal %s with a space. Separate the two characters
            // into successive invocations and quote each argument for the device shell.
            value!!.split(Regex("(?<=%)(?=s)")).joinToString(" && ") {
                "input text '" + it.replace("'", "'\\''") + "'"
            }
        }
    }
}
