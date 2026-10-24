package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage

internal fun nextSourceImportPage(page: MangasPage, currentPage: Int, usesCursor: Boolean): Int? {
    return if (usesCursor) {
        check(page is MetadataMangasPage) { "Missing source pagination metadata" }
        page.nextKey?.let(Math::toIntExact)
    } else {
        (currentPage + 1).takeIf { page.hasNextPage }
    }
}
