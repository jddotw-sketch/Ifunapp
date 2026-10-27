package eu.kanade.tachiyomi.ui.browse.source.browse

/**
 * Assigns imported entries to consecutive categories of at most [capacity] entries.
 * The offset is the number of entries already journaled for this import. This
 * makes the split deterministic on retry, restart, and transaction replay.
 */
internal data class SourceImportCategoryBatch(
    val categoryIndex: Int,
    val ids: List<Long>,
)

internal fun categoryBatches(
    ids: List<Long>,
    completedBefore: Int,
    capacity: Int = 500,
): List<SourceImportCategoryBatch> {
    require(completedBefore >= 0 && capacity > 0)
    if (ids.isEmpty()) return emptyList()
    val result = mutableListOf<SourceImportCategoryBatch>()
    var offset = 0
    while (offset < ids.size) {
        val absolute = completedBefore.toLong() + offset
        val index = (absolute / capacity).toInt()
        val room = (capacity - absolute % capacity).toInt()
        val end = minOf(ids.size, offset + room)
        result += SourceImportCategoryBatch(index, ids.subList(offset, end))
        offset = end
    }
    return result
}

internal fun sourceImportCategoryName(sourceName: String, index: Int): String {
    require(index >= 0)
    return if (index == 0) sourceName else "${sourceName}_${index + 1}"
}
