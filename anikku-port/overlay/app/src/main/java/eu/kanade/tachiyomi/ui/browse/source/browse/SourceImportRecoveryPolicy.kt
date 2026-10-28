package eu.kanade.tachiyomi.ui.browse.source.browse

// Stop only when a user paused the import, a verified terminal page was
// committed, or something is permanently incompatible with this source.
internal class SourceImportPermanentFailure(message: String) : IllegalStateException(message)

internal fun sourceImportShouldRetry(error: Exception): Boolean {
    if (error is SourceImportPermanentFailure) return false
    return error.message !in setOf(
        "Invalid import checkpoint",
        "Unsupported import checkpoint",
        "Damaged import checkpoint",
    )
}

internal fun sourceImportNeedsRecovery(
    status: SourceImportStatus,
    pauseRequested: Boolean,
    activeWork: Boolean,
): Boolean = !pauseRequested && status != SourceImportStatus.FINISHED && !activeWork
