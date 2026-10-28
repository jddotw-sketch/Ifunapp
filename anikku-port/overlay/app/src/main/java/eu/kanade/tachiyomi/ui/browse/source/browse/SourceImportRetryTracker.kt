package eu.kanade.tachiyomi.ui.browse.source.browse

import java.io.File

/**
 * Counts consecutive failures at the same catalog checkpoint.
 * Making progress resets the counter so long imports can survive intermittent errors.
 */
internal class SourceImportRetryTracker(private val file: File) {
    @Synchronized
    fun record(nextKey: Int?): Int {
        val current = nextKey?.toString() ?: "end"
        val old = runCatching { file.readLines() }.getOrNull().orEmpty()
        val attempts = if (old.getOrNull(0) == current) {
            (old.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 24) + 1
        } else {
            1
        }
        // The marker only controls backoff, never whether a manga is saved.
        file.writeText("$current\n$attempts\n")
        return attempts
    }

    fun clear() {
        file.delete()
    }
}
