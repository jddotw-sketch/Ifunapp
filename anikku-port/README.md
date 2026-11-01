# Anikku Library Import: optional AllDebrid torrents

This port continues to apply the custom library importer to Anikku commit
`6e35b41f0cc7e375d15527bd61375435f998685a`. TorrServer is the existing local torrent engine.
It remains the default. No importer source, journal, database migration or importer test is replaced by this change.

## Using AllDebrid

In Settings → Player → Internal player → AllDebrid, enter a key from
<https://alldebrid.com/apikeys>, select **Validate and save**, then enable **Use AllDebrid for torrents**.
Validation requires an active Premium account. Disable the switch to return to TorrServer.
Removing the key also disables AllDebrid. After restoring a backup on another device, enter the key again
or turn the switch off; credentials are never included in app backups.

Magnet links and HTTP(S) `.torrent` URLs (including query parameters) use AllDebrid for
internal playback, downloads, casting and external playback. File lists from `.torrent` metadata are read
locally without an API key, cloud upload, torrent download or local P2P server. This lets Nyaa open its
episode list immediately after fetching the small metadata file. Magnet-only metadata still uses AllDebrid.
The shared resolver also accepts content-URI
torrent files when called by a source. Other videos continue through their original source.
Explicit file indexes use the existing TorrServer convention: 1-based, including non-video files;
index 0 selects the first supported video. No silent selection of another episode occurs for an invalid index.

Uncached torrents can take time to prepare. Requests poll every five seconds for up to two minutes;
the app then asks the user to retry. The cloud job keeps running. A retry in the same process reuses
the submitted transfer. Closing playback cancels the app's request, not the user's cloud job.
The app does not delete AllDebrid transfers. Cloud failures never start local torrenting automatically.
Downloading and casting still depend on video format support in the receiving player.

Nyaa browse cards with missing artwork resolve the extension's normal detail page when Coil requests
the visible card's image. Requests are serialized, cancellable and cached; empty artwork and failures are
temporarily cached to avoid repeated requests. Only the missing thumbnail is saved. Episode lists,
AllDebrid transfers, titles and library membership are not touched by cover loading. Items without artwork
in their description still use the normal placeholder. The attached extension package is supported unchanged.

AllDebrid status/file responses accept either a single magnet object or an array, including string IDs.
Inline ready-file lists are used when available. Service-returned HTTP or HTTPS media URLs are accepted;
the API key is still sent only to the HTTPS API endpoint and never to those media hosts.

## API and credential handling

Uses the supported endpoints documented at <https://docs.alldebrid.com/>:

- `POST /v4/magnet/upload` (`magnets[]`) or `/v4/magnet/upload/file` (`files[]`).
- `POST /v4.1/magnet/status`, `/v4/magnet/files` (`id[]`).
- `POST /v4/link/unlock`, and `/v4/link/delayed` when necessary.
- `GET /v4/user` to validate a key before storing it.

Authorization is a Bearer header sent only to `https://api.alldebrid.com`. A separate OkHttp client has
no logging interceptors, cache, cookies, automatic redirects or connection retries. Requests are serialized
and paced below the documented rate limit. API bodies, credentials and signed playback URLs are not
included in errors. The player/download/external-player paths do not forward source authentication headers
to the cloud download host. HTTP source torrent files use their original source headers without the AllDebrid key.

The key is AES-GCM encrypted with a non-exportable Android Keystore key and atomically stored under
`noBackupFilesDir`, outside all app/source preference backup exports. Password input is masked, omitted
from saved Compose state and protected against screenshots. Corrupt or inaccessible credentials fail closed;
the user can remove and re-enter them. Only the provider opt-in flag uses normal preferences.

## APK identity and updates

The workflow keeps the previous importer APK package **`app.anikku.dev`**. All launcher aliases use
**Anikku Library Import**, with an import-arrow badge on the existing artwork. The version code is
`1000000 + GITHUB_RUN_NUMBER` for this workflow. Preserve the workflow identity and offset in future updates;
if replacing the workflow, choose an offset higher than the last installed APK.

Persistent signing uses these repository Actions secrets:

- `ANIKKU_KEYSTORE_BASE64`
- `ANIKKU_KEYSTORE_PASSWORD`
- `ANIKKU_KEY_ALIAS`
- `ANIKKU_KEY_PASSWORD`

Keep that key for every future build. Never commit it. The configured workflow records certificate fingerprints,
package name, version and label in `anikku-apk-verification.txt`. An APK without secrets is explicitly named
`QA-Ephemeral-Signing` and cannot establish stable update compatibility.

Previous builds generated temporary debug keys on GitHub runners. Unless the original installed APK's private
key is recovered, transitioning to the permanent key requires one final reinstall after backing up the library.
Subsequent APKs built with the permanent key can update in place without deleting the library.
Android device installation/data preservation still needs a device check; assembly/signature verification alone
does not prove compatibility with an unknown installed certificate.

## Verification

The build applies the overlay, restores and merges existing importer translations, merges new Anikku strings
only into `i18n-ank/base`, then runs `spotlessApply`, `spotlessCheck`, importer unit tests,
AllDebrid API/security tests, and `assembleDebug`. The final step verifies the universal APK's signature,
package, versionCode and launcher label. No AllDebrid credential or paid transfer is needed for the mocked tests.
Live account playback and Android Keystore behavior on a physical device are separate manual checks.

No hourly monitoring or scheduled automation is created or enabled.
