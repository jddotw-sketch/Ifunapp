package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceImportCongestionControlTest {
    @Test
    fun previousRateLimitStartsConservativelyAndRecoversGradually() {
        val policy = SourceImportCongestionControl(64, previousRecoveryDelayMillis = 3_000)
        assertEquals(4, policy.clamp(64))
        repeat(63) { policy.onSuccess() }
        assertEquals(4, policy.ceiling)
        policy.onSuccess()
        assertEquals(5, policy.ceiling)
        assertEquals(5, policy.clamp(64))
    }

    @Test
    fun repeatedErrorsHalveConcurrencyWithoutIgnoringManualLimit() {
        val policy = SourceImportCongestionControl(64)
        assertEquals(16, policy.onFailure(32))
        assertEquals(8, policy.onFailure(16))
        assertEquals(4, policy.onFailure(8))
        assertEquals(1, policy.onFailure(1))
        assertEquals(1, policy.clamp(64))
        assertTrue(policy.ceiling <= 64)
    }

    @Test
    fun restoredCapAndSmallConfiguredLimitAreRespected() {
        val policy = SourceImportCongestionControl(2, previousRecoveryDelayMillis = 5_000)
        assertEquals(2, policy.clamp(64))
        repeat(1_000) { policy.onSuccess() }
        assertEquals(2, policy.clamp(64))
    }
}
