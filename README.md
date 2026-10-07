# iFunny Downloader v1.0.3

Replace your repository's main.py, downloader.py, buildozer.spec and intent_filter.xml with these files. Also upload the new storage.py and the .github/workflows/build-apk.yml file.

Run GitHub Actions → Build iFunny Downloader APK → Run workflow. Download and extract the completed APK artifact. Uninstall the previous app if Android reports a signing conflict, then install the new APK.

Share an iFunny post to iFunny Downloader. Success saves to Download/iFunny and closes the app. Errors stay on screen; take a screenshot of the exact stage/message.

## Changes

- Version 1.0.3; package com.local.ifunnyshare.
- Permissions: INTERNET,WRITE_EXTERNAL_STORAGE as requested.
- Write downloaded bytes to an app-cache file, then use Java FileInputStream and android.os.FileUtils.copy to copy into MediaStore.Downloads.
- Close streams before publishing, check the returned byte count, remove failed pending entries, and delete the temporary cache file.
- Show individual save stages. Success is shown only after publishing the MediaStore entry.
- Android 10+ (minimum API 29). WRITE_EXTERNAL_STORAGE does not grant additional shared-storage access on modern Android; MediaStore performs the save.
- No __pycache__ or .pyc files are needed.

## Validation limits

Source syntax and simulated Java/MediaStore success and failure paths were tested. This is a source package to build with GitHub Actions, not a rebuilt APK. It has not been run on an Android phone. The earlier screenshot located the problem in the save stage; it did not prove which individual call was hanging. The new stage messages will narrow that down if necessary.
