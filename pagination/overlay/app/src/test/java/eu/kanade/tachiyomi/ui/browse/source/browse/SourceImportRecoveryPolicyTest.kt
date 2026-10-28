package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SourceImportRecoveryPolicyTest {
    @Test
    fun automaticRecoveryRestartsAbandonedJobsWithoutOverridingPauseOrCompletion() {
        assertTrue(sourceImportNeedsRecovery(SourceImportStatus.QUEUED, false, false))
        assertTrue(sourceImportNeedsRecovery(SourceImportStatus.RUNNING, false, false))
        assertTrue(sourceImportNeedsRecovery(SourceImportStatus.FAILED, false, false))
        assertTrue(sourceImportNeedsRecovery(SourceImportStatus.PAUSED, false, false))
        assertFalse(sourceImportNeedsRecovery(SourceImportStatus.PAUSED, true, false))
        assertFalse(sourceImportNeedsRecovery(SourceImportStatus.FINISHED, false, false))
        assertFalse(sourceImportNeedsRecovery(SourceImportStatus.RUNNING, false, true))
    }

    @Test
    fun networkAndRepeatedPageErrorsRemainRetryableButCorruptionDoesNot() {
        assertTrue(sourceImportShouldRetry(IOException("Connection reset")))
        assertTrue(sourceImportShouldRetry(IllegalStateException("Catalog page 120 repeated")))
        assertFalse(sourceImportShouldRetry(SourceImportPermanentFailure("Source extension unavailable")))
        assertFalse(sourceImportShouldRetry(IllegalStateException("Damaged import checkpoint")))
    }

    @Test
    fun retryCounterDoesNotStopAfterTwentyFourAttemptsAndResetsWithProgress() {
        val dir = Files.createTempDirectory("source-import-retry-unlimited").toFile()
        try {
            val tracker = SourceImportRetryTracker(File(dir, "retry-state"))
            repeat(40) { attempt -> assertEquals(attempt + 1, tracker.record(420)) }
            assertEquals(1, tracker.record(421))
            assertEquals(2, SourceImportRetryTracker(File(dir, "retry-state")).record(421))
        } finally {
            dir.deleteRecursively()
        }
    }
}
