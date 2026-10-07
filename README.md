# iFunny Downloader v1.1.0 — background downloads

Upload ALL the files/folders in this ZIP to your repository, including java/, service.py, storage.py, and .github/workflows/build-apk.yml. Replace the earlier buildozer.spec and main.py. Run GitHub Actions → Build iFunny Downloader APK → Run workflow.

Install the APK. Open iFunny Downloader once and allow notifications. This first opening also extracts the packaged Python assets. Then close it.

Share a post → choose iFunny Downloader → immediately return to the previous app. Android may briefly show its launch transition. No waiting for the download screen. If your very first action after installing is sharing, one-time setup runs first.

The download runs in a separate foreground-service process. An ongoing download notification is displayed, followed by a success or failure notification. Tap a success notification to open the file if an installed app supports that media type. Files still save to Download/iFunny, using the working v1.0.4 Java stream copy implementation.

Further shared posts queue while the service runs. It stops automatically three seconds after the queue is empty. Ordinary navigation or removing the launcher's task does not cancel the download. Force-stopping the app or Android terminating its process can cancel active/queued work; there is no persistent retry queue in this version.

If notifications were denied, allow them in Android Settings → Apps → iFunny Downloader → Notifications. Downloads still work when notifications are denied. The setup screen shows the last saved success/failure message.

Permissions keep INTERNET and WRITE_EXTERNAL_STORAGE, and add POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC and WAKE_LOCK. On Android 10+, MediaStore owns the shared-file write path. On Android 13+, notification permission requires a one-time user choice.

## Build and implementation

- Main manifest entry point: com.local.ifunnyshare.ShareActivity (native Activity).
- Existing intent_filter.xml remains attached with android.manifest.intent_filters.
- org.kivy.android.PythonActivity is a separate internal setup Activity.
- Generated ServiceDownloader extends DownloadServiceBase and runs service.py in a separate process.
- A dataSync foreground service handles downloads; the Java service launches its notification immediately, without waiting for Python startup.
- Download progress, success and failure notifications use separate channels.
- Python-for-Android is pinned to 94ffd5f31d816414ad1fe66c0fe587c61daac757 on develop to keep the inspected service/manifest API stable.
- Error notifications show the actual exception. No __pycache__ or .pyc upload is needed.

Validation: Python syntax; simulated worker queue success, download failure and save failure; native Java compilation against Android API 35 with inspected p4a service sources and minimal bootstrap stubs; rendered upstream manifest verified. GitHub Actions now checks the generated manifest before uploading the APK. This is source for GitHub Actions, not a prebuilt or phone-tested APK.
