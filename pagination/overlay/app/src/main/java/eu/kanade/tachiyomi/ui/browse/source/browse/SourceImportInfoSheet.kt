package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.BuildConfig
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceImportInfoSheet(
    sourceName: String,
    stats: SourceImportStats,
    onDismiss: () -> Unit,
    onStop: (() -> Unit)?,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val mode = stringResource(
        when (stats.mode) {
            SourceImportStats.Mode.PREPARING -> KMR.strings.source_import_mode_preparing
            SourceImportStats.Mode.PARALLEL -> KMR.strings.source_import_mode_parallel
            SourceImportStats.Mode.SINGLE -> KMR.strings.source_import_mode_single
            SourceImportStats.Mode.SLOW -> KMR.strings.source_import_mode_slow
            SourceImportStats.Mode.CURSOR -> KMR.strings.source_import_mode_cursor
            SourceImportStats.Mode.ERROR_FALLBACK -> KMR.strings.source_import_mode_error
            SourceImportStats.Mode.REPEATED_FALLBACK -> KMR.strings.source_import_mode_repeated
        },
    )
    val bottleneck = stringResource(
        when (stats.bottleneck) {
            SourceImportStats.Bottleneck.STARTING -> KMR.strings.source_import_limit_starting
            SourceImportStats.Bottleneck.RECOVERY -> KMR.strings.source_import_limit_recovery
            SourceImportStats.Bottleneck.LOADING -> KMR.strings.source_import_limit_loading
            SourceImportStats.Bottleneck.SAVING -> KMR.strings.source_import_limit_saving
            SourceImportStats.Bottleneck.BALANCED -> KMR.strings.source_import_limit_balanced
        },
    )
    val noSample = stringResource(KMR.strings.source_import_no_sample)
    val rows = listOf(
        stringResource(KMR.strings.source_import_auto) to stringResource(
            when {
                !stats.automatic -> KMR.strings.source_import_auto_manual
                stats.mode != SourceImportStats.Mode.PARALLEL -> KMR.strings.source_import_auto_restricted
                stats.autoSearching -> KMR.strings.source_import_auto_testing
                else -> KMR.strings.source_import_auto_settled
            },
        ),
        stringResource(KMR.strings.source_import_build) to "${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TIME}",
        stringResource(KMR.strings.source_import_elapsed) to stringResource(
            KMR.strings.source_import_seconds,
            String.format(Locale.ROOT, "%.1f", stats.elapsedMillis / 1_000.0),
        ),
        stringResource(KMR.strings.source_import_restored) to "${stats.restoredItems} / ${stats.restoredPages}",
        stringResource(KMR.strings.source_import_preload) to stats.preloadPages.toString(),
        stringResource(KMR.strings.source_import_effective) to stats.effectiveConcurrency.toString(),
        stringResource(KMR.strings.source_import_active) to stats.activeLoads.toString(),
        stringResource(KMR.strings.source_import_buffered) to stats.bufferedPages.toString(),
        stringResource(KMR.strings.source_import_loaded) to stats.loadedPages.toString(),
        stringResource(KMR.strings.source_import_saved) to stats.savedPages.toString(),
        stringResource(KMR.strings.source_import_counts) to "${stats.added} / ${stats.existing}",
        stringResource(KMR.strings.source_import_recent_rate) to String.format(Locale.ROOT, "%.1f", stats.recentItemsPerSecond),
        stringResource(KMR.strings.source_import_average_rate) to String.format(Locale.ROOT, "%.1f", stats.averageItemsPerSecond),
        stringResource(KMR.strings.source_import_page_rate) to String.format(Locale.ROOT, "%.1f", stats.pagesPerMinute),
        stringResource(KMR.strings.source_import_load_time) to if (stats.loadedPages == 0) noSample else {
            stringResource(KMR.strings.source_import_milliseconds, stats.averageLoadMillis)
        },
        stringResource(KMR.strings.source_import_save_time) to if (stats.savedPages == stats.restoredPages) noSample else {
            stringResource(KMR.strings.source_import_milliseconds, stats.averageSaveMillis)
        },
        stringResource(KMR.strings.source_import_wait_time) to stringResource(KMR.strings.source_import_milliseconds, stats.waitingMillis),
        stringResource(KMR.strings.source_import_busy_time) to stringResource(KMR.strings.source_import_milliseconds, stats.savingMillis),
        stringResource(KMR.strings.source_import_queue_time) to stringResource(KMR.strings.source_import_milliseconds, stats.queueWaitMillis),
        stringResource(KMR.strings.source_import_error_counts) to "${stats.errors} / ${stats.recoveries}",
    )
    val explanation = stringResource(KMR.strings.source_import_diagnostics_note)
    val lastSlowdown = stats.lastSlowdown
    val slowdownHistory = stats.slowdownHistory.takeLast(5).asReversed()
    val status = stringResource(if (stats.finished) KMR.strings.source_import_finished else KMR.strings.source_import_running)
    val report = buildString {
        appendLine(sourceName)
        appendLine(status)
        appendLine(mode)
        appendLine(bottleneck)
        rows.forEach { (label, value) -> appendLine("$label: $value") }
        lastSlowdown?.let { issue ->
            appendLine("Last slowdown: ${issue.cause}")
            appendLine("Page: ${issue.pageKey ?: "unknown"}")
            appendLine("Automatic response: ${issue.action}")
            issue.retryIntervalMillis?.let { appendLine("Retry interval: ${it / 1_000.0} seconds") }
            appendLine("Occurred ${issue.atElapsedMillis / 1_000.0} seconds into this run")
        }
        if (slowdownHistory.isNotEmpty()) {
            appendLine("Recent slowdown history:")
            slowdownHistory.forEach { issue ->
                appendLine("Page ${issue.pageKey ?: "?"}: ${issue.cause}; ${issue.action}")
            }
        }
        append(explanation)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(KMR.strings.source_import_info), style = MaterialTheme.typography.titleLarge)
            Text(sourceName)
            Text(status)
            Text(mode, style = MaterialTheme.typography.bodySmall)
            Text(bottleneck, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            stats.retryDelayMillis?.takeUnless { stats.finished }?.let {
                Text(stringResource(KMR.strings.source_import_retrying, it / 1_000))
            }
            lastSlowdown?.let { issue ->
                HorizontalDivider()
                Text("Most recent error slowdown", style = MaterialTheme.typography.titleMedium)
                Text("Page: ${issue.pageKey ?: "unknown"}")
                Text("Cause: ${issue.cause}")
                Text("Recovery action: ${issue.action}")
                issue.retryIntervalMillis?.let {
                    Text("Retry interval: ${String.format(Locale.ROOT, "%.1f", it / 1_000.0)} seconds")
                }
                Text(
                    "Occurred ${String.format(Locale.ROOT, "%.1f", issue.atElapsedMillis / 1_000.0)} seconds into this run",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (slowdownHistory.size > 1) {
                Text("Recent slowdowns", style = MaterialTheme.typography.titleSmall)
                slowdownHistory.forEach { issue ->
                    Text(
                        "Page ${issue.pageKey ?: "?"} · ${issue.cause} · ${issue.action}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(explanation, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(sourceName, report))
                        copied = true
                    },
                ) {
                    Text(stringResource(if (copied) KMR.strings.source_import_copied else KMR.strings.source_import_copy_info))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(KMR.strings.source_import_close_info)) }
            }
            onStop?.let { stop ->
                TextButton(onClick = stop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(KMR.strings.source_import_pause))
                }
            }
        }
    }
}
