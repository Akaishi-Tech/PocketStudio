package com.hcgstudio.pocketstudio

private const val LAST_GRAPH_LANE = 4
private const val GRAPH_COLORS = 3

internal data class GitGraphLine(val from: Int, val to: Int, val color: Int)
internal data class GitGraphRow(
    val node: Int, val color: Int, val lanes: Int,
    val incoming: Set<GitGraphLine>, val passing: Set<GitGraphLine>, val outgoing: Set<GitGraphLine>
)

/** Each pending lane reaches its own parent, even when another lane leads to the same commit. */
internal fun gitGraph(commits: List<GitCommitItem>): List<GitGraphRow> {
    data class Pending(val target: String, val color: Int)
    var pending = emptyList<Pending>()
    var nextColor = 0

    // Wide histories share a muted outer lane after the fourth independent track.
    fun edge(from: Int, to: Int, color: Int) = GitGraphLine(minOf(from, LAST_GRAPH_LANE),
        minOf(to, LAST_GRAPH_LANE), if (from >= LAST_GRAPH_LANE && to >= LAST_GRAPH_LANE) -1 else color)

    return commits.map { commit ->
        val node = pending.indexOfFirst { it.target == commit.id }.let { if (it < 0) pending.size else it }
        val color = pending.getOrNull(node)?.color ?: (nextColor++ % GRAPH_COLORS)
        val incoming = mutableSetOf<GitGraphLine>()
        val outgoing = mutableSetOf<GitGraphLine>()
        val passing = mutableSetOf<GitGraphLine>()
        val next = mutableListOf<Pending>()
        for (position in 0..pending.size) {
            if (position == node) commit.parents.forEachIndexed { index, parent ->
                val laneColor = if (index == 0) color else nextColor++ % GRAPH_COLORS
                outgoing += edge(node, next.size, laneColor)
                next += Pending(parent, laneColor)
            }
            val lane = pending.getOrNull(position) ?: break
            if (lane.target == commit.id) incoming += edge(position, node, lane.color)
            else {
                passing += edge(position, next.size, lane.color)
                next += lane
            }
        }
        val width = maxOf(pending.size, next.size, node + 1).coerceAtMost(LAST_GRAPH_LANE + 1)
        pending = next
        GitGraphRow(minOf(node, LAST_GRAPH_LANE), color, width, incoming, passing, outgoing)
    }
}
