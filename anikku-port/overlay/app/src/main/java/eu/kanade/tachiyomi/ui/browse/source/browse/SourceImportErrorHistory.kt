package eu.kanade.tachiyomi.ui.browse.source.browse

import java.io.File
import java.util.Base64

/** Bounded local error history, preserved when an importer worker restarts. */
internal class SourceImportErrorHistory(private val file: File) {
    private fun sanitize(s: String): String = s
        .replace(Regex("""https?://\S+"""), "[URL redacted]")
        .replace(Regex("""(?i)(bearer|authorization|cookie|token|password|api[_-]?key)\s*[:=]\s*\S+"""), "[credential redacted]")
        .take(320)

    @Synchronized
    fun add(page: Int?, error: Throwable?, action: String) {
        val encoded = listOf(
            System.currentTimeMillis().toString(),
            page?.toString() ?: "?",
            sanitize(error?.javaClass?.simpleName ?: "PageWarning"),
            sanitize(error?.message ?: "Repeated or missing page"),
            sanitize(sourceImportExplainFailure(error)),
            sanitize(action),
        ).joinToString("|") { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
        file.parentFile?.mkdirs()
        val entries = runCatching { file.readLines() }.getOrNull().orEmpty().takeLast(29) + encoded
        file.writeText(entries.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun recent(): List<String> = runCatching { file.readLines() }.getOrNull().orEmpty()
        .takeLast(20).mapNotNull { line ->
            runCatching {
                val c = line.split("|").map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
                if (c.size != 6) null else c[0] + " | Page " + c[1] + " | " + c[2] + ": " +
                    c[3] + " | " + c[4] + " | " + c[5]
            }.getOrNull()
        }
}
