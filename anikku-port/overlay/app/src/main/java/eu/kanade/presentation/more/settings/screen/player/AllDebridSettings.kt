package eu.kanade.presentation.more.settings.screen.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.alldebrid.AllDebridClient
import eu.kanade.tachiyomi.alldebrid.AllDebridException
import eu.kanade.tachiyomi.alldebrid.AllDebridFailure
import eu.kanade.tachiyomi.alldebrid.AllDebridKeyStore
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.torrentServer.TorrentServerPreferences
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// ANK: Only the enabled flag is a normal preference. The credential has its own Keystore-backed file.
@Composable
internal fun getAllDebridGroup(preferences: TorrentServerPreferences): Preference.PreferenceGroup {
    val keys = remember { Injekt.get<AllDebridKeyStore>() }
    val api = remember { Injekt.get<AllDebridClient>() }
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(runCatching { keys.read().isNotBlank() }.getOrDefault(false)) }
    var dialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AllDebridFailure?>(null) }

    if (dialog) {
        AllDebridKeyDialog(
            onDismiss = { dialog = false },
            onSaved = { configured = true; dialog = false; error = null },
        )
    }
    return Preference.PreferenceGroup(
        title = stringResource(AMR.strings.alldebrid_title),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.useAllDebrid(),
                title = stringResource(AMR.strings.alldebrid_enable),
                subtitle = stringResource(AMR.strings.alldebrid_summary),
                enabled = configured,
                onValueChanged = {
                    if (it) TorrentServerService.stop()
                    true
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(AMR.strings.alldebrid_key),
                subtitle = stringResource(if (configured) AMR.strings.alldebrid_configured else AMR.strings.alldebrid_not_configured),
                onClick = { dialog = true },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(AMR.strings.alldebrid_forget),
                subtitle = error?.let { stringResource(it.messageResource()) },
                // Allow removal even when the stored key has become unreadable.
                onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { keys.write("") }
                            preferences.useAllDebrid().set(false)
                            api.clearSession()
                            configured = false
                            error = null
                        } catch (e: AllDebridException) {
                            error = e.failure
                        }
                    }
                },
            ),
        ),
    )
}

@Composable
private fun AllDebridKeyDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    // Never prefill or rememberSaveable the secret. Dismissing destroys the entered value.
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AllDebridFailure?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(AMR.strings.alldebrid_key)) },
        text = {
            Column {
                Text(stringResource(AMR.strings.alldebrid_key_help))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; error = null },
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = error != null,
                )
                error?.let { Text(stringResource(it.messageResource())) }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && key.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val api = Injekt.get<AllDebridClient>()
                            val value = key.trim()
                            api.validateKey(value)
                            withContext(Dispatchers.IO) { Injekt.get<AllDebridKeyStore>().write(value) }
                            api.clearSession()
                            key = ""
                            onSaved()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: AllDebridException) {
                            error = e.failure
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(if (busy) MR.strings.loading else AMR.strings.alldebrid_validate_save)) }
        },
    )
}

internal fun AllDebridFailure.messageResource() = when (this) {
    AllDebridFailure.KEY -> AMR.strings.alldebrid_error_key
    AllDebridFailure.ACCOUNT -> AMR.strings.alldebrid_error_account
    AllDebridFailure.BLOCKED -> AMR.strings.alldebrid_error_blocked
    AllDebridFailure.RATE_LIMIT -> AMR.strings.alldebrid_error_rate
    AllDebridFailure.PENDING -> AMR.strings.alldebrid_error_pending
    AllDebridFailure.FILE -> AMR.strings.alldebrid_error_file
    AllDebridFailure.STORAGE -> AMR.strings.alldebrid_error_storage
    AllDebridFailure.TORRENT -> AMR.strings.alldebrid_error_torrent
    AllDebridFailure.NETWORK, AllDebridFailure.RESPONSE -> AMR.strings.alldebrid_error_network
}
