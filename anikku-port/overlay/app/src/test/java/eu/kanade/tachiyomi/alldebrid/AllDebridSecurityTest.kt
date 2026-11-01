package eu.kanade.tachiyomi.alldebrid

import eu.kanade.tachiyomi.torrentServer.TorrentServerPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class AllDebridSecurityTest {
    @Test
    fun credentialsAreAuthenticatedEncryptedWithFreshNonces() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AllDebridCredentialCipher { key }
        val secret = "private-api-key"
        val first = cipher.encrypt(secret)
        val second = cipher.encrypt(secret)
        assertEquals(secret, cipher.decrypt(first))
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains(secret))
        val damaged = first.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(damaged) }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(byteArrayOf(1)) }
    }

    @Test
    fun localTorrServerRemainsTheDefaultAndOnlyTheOptInFlagUsesPreferences() {
        val store = mockk<PreferenceStore>()
        val enabled = mockk<Preference<Boolean>>()
        every { store.getBoolean("pref_alldebrid_enabled", false) } returns enabled
        every { enabled.get() } returns false
        assertFalse(TorrentServerPreferences(store).useAllDebrid().get())
        verify(exactly = 1) { store.getBoolean("pref_alldebrid_enabled", false) }
        verify(exactly = 0) { store.getString(any(), any()) }
    }
}
