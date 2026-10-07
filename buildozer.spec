[app]
title = iFunny Downloader
package.name = ifunnyshare
package.domain = com.local
source.dir = .
source.include_exts = py,xml,png,jpg,kv
source.exclude_dirs = tests,__pycache__,.git,.github,bin
version = 1.0.4
requirements = python3,kivy,pyjnius,requests,beautifulsoup4,pillow
orientation = portrait
fullscreen = 0
android.permissions = INTERNET,WRITE_EXTERNAL_STORAGE
android.api = 35
android.minapi = 29
android.archs = arm64-v8a
android.accept_sdk_license = True
android.manifest.intent_filters = intent_filter.xml
android.add_compile_options = "sourceCompatibility = 1.8", "targetCompatibility = 1.8"

[buildozer]
log_level = 2
warn_on_root = 1
