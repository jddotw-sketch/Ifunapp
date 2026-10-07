[app]
title = iFunny Downloader
package.name = ifunnyshare
package.domain = com.local
source.dir = .
source.include_exts = py,xml,png,jpg,kv,atlas
version = 1.0.0
requirements = python3,kivy,requests,beautifulsoup4,pillow,pyjnius
orientation = portrait
fullscreen = 0

# API 29+ lets the app save to Download/iFunny using MediaStore without broad
# storage permission.
android.minapi = 29
android.api = 35
android.archs = arm64-v8a
android.manifest.intent_filters = intent_filter.xml
android.manifest.launch_mode = singleTask
android.accept_sdk_license = True

[buildozer]
log_level = 2
warn_on_root = 1
