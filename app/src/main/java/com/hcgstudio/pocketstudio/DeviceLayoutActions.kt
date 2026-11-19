package com.hcgstudio.pocketstudio

import android.graphics.Rect
import android.util.Base64
import com.hcgstudio.pocketstudio.adb.Target
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** A snapshot reference belongs to one ADB connection. Resolve it again before sending any input. */
internal class DeviceLayoutActions(val target: Target) {
    private data class Node(val identity: String, val depth: Int, val area: Rect, val enabled: Boolean,
        val clickable: Boolean, val longClickable: Boolean, val scrollable: Boolean, val editable: Boolean,
        val focused: Boolean)
    private data class Ref(val identity: String, val index: Int, val area: Rect)
    private data class Snapshot(val refs: Map<String, Ref>, val duplicates: Set<String>, val revision: ByteArray?)
    private val lock = Mutex()
    private var snapshot: Snapshot? = null
    @Volatile private var disconnected = false

    fun disconnect() { disconnected = true }

    private fun revision(xml: String) = MessageDigest.getInstance("SHA-256").digest(xml.toByteArray(Charsets.UTF_8))

    suspend fun inspect(): String = lock.withLock {
        snapshot = null
        val generation = UUID.randomUUID().toString().replace("-", "").take(12)
        val nodes = JSONArray()
        val byRef = mutableMapOf<String, Ref>()
        val seen = mutableSetOf<String>()
        val duplicates = mutableSetOf<String>()
        var truncated = false
        var full = false
        var length = 0
        val hierarchy = DeviceAgentTools.layoutXml(target)
        visit(hierarchy) { xml, node ->
            // Continue through the entire tree: a duplicate may be outside the response budget.
            if (node.identity in seen) duplicates.add(node.identity)
            if (full) return@visit
            val ref = "e${nodes.length() + 1}-$generation"
            val area = node.area
            val entry = json("ref" to ref, "depth" to node.depth, "bounds" to JSONArray()
                .put(area.left).put(area.top).put(area.right).put(area.bottom))
            val password = xml.flag("password")
            for (name in listOf("package", "class", "resource-id", "text", "content-desc")) {
                if (password && (name == "text" || name == "content-desc")) continue
                val value = xml.getAttributeValue(null, name)
                if (value.isNullOrEmpty()) continue
                val end = if (value.length > 512 && value[511].isHighSurrogate()) 511 else 512
                entry.put(name, value.take(end))
                if (value.length > end) truncated = true
            }
            if (password) entry.put("password", true)
            entry.put("enabled", node.enabled)
            entry.put("clickable", node.clickable)
            if (node.longClickable) entry.put("long-clickable", true)
            if (node.scrollable) entry.put("scrollable", true)
            if (node.editable) entry.put("editable", true)
            if (node.focused) entry.put("focused", true)
            length += entry.toString().length + 1
            if (nodes.length() >= 200 || length > 24000) {
                truncated = true
                full = true
            } else {
                seen.add(node.identity)
                byRef[ref] = Ref(node.identity, nodes.length(), area)
                nodes.put(entry)
            }
        }
        snapshot = Snapshot(byRef, duplicates, if (duplicates.isEmpty()) null else revision(hierarchy))
        json("nodes" to nodes, "truncated" to truncated).toString()
    }

    private fun XmlPullParser.flag(name: String) = getAttributeValue(null, name) == "true"

    private fun visit(xml: String, accept: (XmlPullParser, Node) -> Unit) {
        val parents = ArrayDeque<Node>()
        val digest = MessageDigest.getInstance("SHA-256")
        DeviceInspector.visitHierarchy(xml) { element, area, _ ->
            val depth = element.depth - 2
            while (parents.isNotEmpty() && parents.last().depth >= depth) parents.removeLast()
            val parent = parents.lastOrNull()
            val type = element.getAttributeValue(null, "class").orEmpty()
            val editable = element.flag("editable") || type.endsWith("EditText")
            val password = element.flag("password")
            // Bounds, focus, and editable values may change while this same control is visible.
            // Keep ancestor identity so identical controls in distinct labelled groups stay distinct.
            for (value in arrayOf(parent?.identity, element.getAttributeValue(null, "package"), type,
                element.getAttributeValue(null, "resource-id"), if (password) null else element.getAttributeValue(null, "content-desc"),
                if (editable || password) null else element.getAttributeValue(null, "text"))) {
                digest.update(value.orEmpty().toByteArray(Charsets.UTF_8))
                digest.update(0.toByte()) // XML attributes cannot contain NUL.
            }
            val identity = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
            val enabled = element.flag("enabled")
            val node = Node(identity, depth, area ?: Rect(), enabled,
                (area != null && element.flag("clickable")) || parent?.let { it.enabled && it.clickable } == true,
                (area != null && element.flag("long-clickable")) || parent?.let { it.enabled && it.longClickable } == true,
                element.flag("scrollable"), editable, element.flag("focused"))
            parents.addLast(node)
            if (area != null) accept(element, node)
            true
        }
    }

    private suspend fun resolve(observed: Snapshot, ref: Ref): Node {
        val xml = DeviceAgentTools.layoutXml(target)
        val repeated = ref.identity in observed.duplicates
        if (repeated && !revision(xml).contentEquals(observed.revision)) stale()
        val point = inputArea(ref.area)
        var match: Node? = null
        var count = 0
        var index = 0
        visit(xml) { _, node ->
            if (node.identity == ref.identity) {
                if (!repeated || index == ref.index) match = node
                // Equal controls can use their observed positions only in a byte-identical tree.
                // If two instances cover the chosen point, an input gesture cannot select one.
                if (!repeated || node.area.contains(point.centerX(), point.centerY())) count++
            }
            index++
        }
        val node = match ?: stale()
        if (count != 1) stale()
        if (!node.enabled) throw IOException(uiString(R.string.agent_device_node_disabled))
        return node
    }

    private fun stale(): Nothing = throw IOException(uiString(R.string.agent_device_node_stale))

    private fun inputArea(area: Rect): Rect = Rect(area).also { if (!it.intersect(0, 0, 65536, 65536)) stale() }

    private suspend fun send(args: JSONObject) = DeviceAgentTools.act(target, args) {
        if (disconnected || !target.isConnected) throw IOException(uiString(R.string.target_device_disconnected))
        snapshot = null
    }

    /** All Agent input on this connection shares the same lock as layout resolution and focusing. */
    suspend fun input(args: JSONObject): String = lock.withLock { send(args) }

    suspend fun perform(toolName: String, args: JSONObject): String = lock.withLock {
        val extra = when (toolName) {
            "click_device_node", "long_press_device_node" -> null
            "type_device_node" -> "text"
            "scroll_device_node" -> "direction"
            else -> throw IllegalArgumentException(uiString(R.string.unsupported_tool))
        }
        for (key in args.keys()) require(key == "ref" || key == extra) { uiString(R.string.agent_device_input_invalid, key) }
        val ref = args.opt("ref") as? String
        require(!ref.isNullOrEmpty()) { uiString(R.string.agent_device_input_invalid, "ref") }
        val observed = snapshot ?: stale()
        val selected = observed.refs[ref] ?: stale()
        val node = resolve(observed, selected)
        val area = inputArea(node.area)
        fun press(action: String) = json("action" to action, "x" to area.centerX(), "y" to area.centerY())
        fun available(condition: Boolean) { if (!condition) throw IOException(uiString(R.string.agent_device_node_unsupported)) }
        when (toolName) {
            "click_device_node" -> {
                available(node.clickable)
                send(press("tap"))
            }
            "long_press_device_node" -> {
                available(node.longClickable)
                send(press("long_press"))
            }
            "type_device_node" -> {
                available(node.editable)
                val text = json("action" to "text", "text" to args.opt("text"))
                deviceInputCommand(text) // Validate before moving focus.
                if (!node.focused) {
                    send(press("tap"))
                    val focused = resolve(observed, selected)
                    available(focused.editable && focused.focused)
                }
                send(text)
            }
            "scroll_device_node" -> {
                available(node.scrollable)
                val direction = args.opt("direction") as? String
                require(direction in listOf("up", "down", "left", "right")) {
                    uiString(R.string.agent_device_input_invalid, "direction")
                }
                val x = area.centerX()
                val y = area.centerY()
                val dx = when (direction) { "left" -> -area.width() / 4; "right" -> area.width() / 4; else -> 0 }
                val dy = when (direction) { "up" -> -area.height() / 4; "down" -> area.height() / 4; else -> 0 }
                available(dx != 0 || dy != 0)
                send(json("action" to "swipe", "x" to x + dx, "y" to y + dy,
                    "endX" to x - dx, "endY" to y - dy))
            }
        }
        json("action" to toolName, "ok" to true).toString()
    }
}
