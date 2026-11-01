package eu.kanade.tachiyomi.alldebrid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

// ANK: Separate from PreferenceStore and all app/source backup exports. Android backups are disabled.
class AndroidAllDebridKeyStore(context: Context) : AllDebridKeyStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "alldebrid-key"))
    private val cipher = AllDebridCredentialCipher(::key)

    @Synchronized
    override fun read(): String = protect {
        if (!file.baseFile.exists()) return@protect ""
        cipher.decrypt(file.readFully())
    }

    @Synchronized
    override fun write(value: String) = protect {
        if (value.isBlank()) {
            file.delete()
        } else {
            if (value.length > 1024 || value.any { it <= ' ' || it >= '\u007f' }) throw AllDebridException(AllDebridFailure.KEY)
            val encrypted = cipher.encrypt(value)
            val output = file.startWrite()
            try {
                output.write(encrypted)
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
            generateKey()
        }
    }

    private inline fun <T> protect(block: () -> T): T = try {
        block()
    } catch (e: AllDebridException) {
        throw e
    } catch (_: Exception) {
        throw AllDebridException(AllDebridFailure.STORAGE)
    }

    companion object {
        private const val ALIAS = "anikku.alldebrid.v1"
    }
}
