package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceImportSlowdownTest {
    @Test
    fun failedPageRecordsCauseAndWaitEvenAfterRetryEnds() {
        var clock = 0L
        val telemetry = SourceImportTelemetry(8, nowMillis = { clock })
        telemetry.requestStarted()
        clock += 2_000L
        telemetry.requestFailed(42, IOException("Connection timed out"))
        telemetry.retry(3_000L)
        val during = telemetry.snapshot().lastSlowdown
        assertNotNull(during)
        assertEquals(42, during?.pageKey)
        assertEquals("Source request timed out", during?.cause)
        assertEquals(3_000L, during?.retryIntervalMillis)
        assertEquals(SourceImportStats.Bottleneck.RECOVERY, telemetry.snapshot().bottleneck)
        telemetry.retry(null)
        assertEquals(during, telemetry.snapshot().lastSlowdown)
    }

    @Test
    fun repeatedPageRecordsRecoveryActionAndHistory() {
        val telemetry = SourceImportTelemetry(8, nowMillis = { 10_000L })
        telemetry.slowdown(51, null, "Switching to one request at a time")
        val first = telemetry.snapshot().lastSlowdown
        assertEquals("Repeated page or no new catalog entries", first?.cause)
        assertEquals(51, first?.pageKey)
        assertEquals("Switching to one request at a time", first?.action)
        telemetry.slowdown(52, IllegalStateException("HTTP 429"), "Retrying in order")
        val stats = telemetry.snapshot()
        assertEquals("HTTP 429: source is rate-limiting requests", stats.lastSlowdown?.cause)
        assertEquals(2, stats.slowdownHistory.size)
        assertTrue(stats.slowdownHistory.size <= 5)
    }

    @Test
    fun distinguishesNetworkErrorFromDatabaseSavingError() {
        assertEquals("Source connection failed", sourceImportExplainFailure(IOException("connection refused")))
        assertEquals("Local database write failed", sourceImportExplainFailure(IllegalStateException("SQLite constraint failed")))
        assertEquals("Source request timed out", sourceImportExplainFailure(IOException("read timed out")))
    }
}
