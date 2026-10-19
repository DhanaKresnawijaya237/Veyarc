package com.veyarc.feature.assistant

internal data class AssistantTextDiff(val before: List<IntRange>, val after: List<IntRange>)

internal data class AssistantDiffLine(val text: String, val changed: List<IntRange>)

/** Keep changed rows only; ranges are relative to each row for stronger inline emphasis. */
internal fun changedDiffLines(value: String, ranges: List<IntRange>): List<AssistantDiffLine> = buildList {
    var offset = 0
    value.split('\n').forEach { line ->
        val end = offset + line.length
        val edits = ranges.filter { !it.isEmpty() && it.first <= end && it.last >= offset }
        if (edits.isNotEmpty()) {
            add(
                AssistantDiffLine(
                    line,
                    edits.mapNotNull {
                        val first = maxOf(offset, it.first) - offset
                        val last = minOf(end - 1, it.last) - offset
                        (first..last).takeUnless(IntRange::isEmpty)
                    },
                ),
            )
        }
        offset = end + 1
    }
}

/** Display-only ranges; the original proposal remains unchanged. */
internal fun changedTextRanges(before: String?, after: String?): AssistantTextDiff {
    fun all(text: String?) = if (text.isNullOrEmpty()) emptyList() else listOf(text.indices)
    if (before == null || after == null) return AssistantTextDiff(all(before), all(after))
    if (before == after) return AssistantTextDiff(emptyList(), emptyList())
    val tokens = Regex("\\s+|[\\p{L}\\p{N}_]+|[^\\s\\p{L}\\p{N}_]")
    val a = tokens.findAll(before).toList()
    val b = tokens.findAll(after).toList()
    var start = 0
    while (start < minOf(a.size, b.size) && a[start].value == b[start].value) start++
    var endA = a.size
    var endB = b.size
    while (endA > start && endB > start && a[endA - 1].value == b[endB - 1].value) {
        endA--
        endB--
    }
    val changedA = BooleanArray(a.size)
    val changedB = BooleanArray(b.size)
    val rows = endA - start
    val columns = endB - start
    // shortcut: large replaced fields keep common edges; use a linear-space diff if finer ranges are needed.
    if ((rows.toLong() + 1) * (columns.toLong() + 1) > 65_536) {
        changedA.fill(true, start, endA)
        changedB.fill(true, start, endB)
    } else {
        val stride = columns + 1
        val lengths = IntArray((rows + 1) * stride)
        for (i in rows - 1 downTo 0) {
            for (j in columns - 1 downTo 0) {
                lengths[i * stride + j] = if (a[start + i].value == b[start + j].value) 1 + lengths[(i + 1) * stride + j + 1] else maxOf(lengths[(i + 1) * stride + j], lengths[i * stride + j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < rows || j < columns) {
            when {
                i < rows && j < columns && a[start + i].value == b[start + j].value -> {
                    i++
                    j++
                }

                i < rows && (j == columns || lengths[(i + 1) * stride + j] >= lengths[i * stride + j + 1]) -> changedA[start + i++] = true

                else -> changedB[start + j++] = true
            }
        }
    }
    fun ranges(matches: List<MatchResult>, changed: BooleanArray): List<IntRange> = buildList {
        var first = -1
        for (index in 0..matches.size) {
            if (index < matches.size && changed[index]) {
                if (first < 0) first = index
            } else if (first >= 0) {
                add(matches[first].range.first..matches[index - 1].range.last)
                first = -1
            }
        }
    }
    return AssistantTextDiff(ranges(a, changedA), ranges(b, changedB))
}
