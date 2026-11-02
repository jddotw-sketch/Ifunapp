package eu.kanade.tachiyomi.alldebrid

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ANK: The key provider is Android Keystore in production; plaintext is never stored.
class AllDebridCredentialCipher(private val key: () -> SecretKey) {
    fun encrypt(value: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key())
        iv + doFinal(value.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(bytes: ByteArray): String {
        require(bytes.size in 29..4096)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
}
