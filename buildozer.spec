[app]
title = iFunny Downloader
package.name = ifunnyshare
package.domain = com.local
source.dir = .
source.include_exts = py,xml,png,jpg,kv,java
source.exclude_dirs = tests,__pycache__,.git,.github,bin
version = 1.1.2
requirements = python3,kivy,pyjnius,requests,beautifulsoup4,pillow
orientation = portrait
fullscreen = 0
android.permissions = INTERNET,WRITE_EXTERNAL_STORAGE,POST_NOTIFICATIONS,FOREGROUND_SERVICE,FOREGROUND_SERVICE_DATA_SYNC,WAKE_LOCK
android.api = 35
android.minapi = 29
android.archs = arm64-v8a
android.accept_sdk_license = True
android.manifest.intent_filters = intent_filter.xml
android.add_compile_options = "sourceCompatibility = 1.8", "targetCompatibility = 1.8"

# Native receiver handles shares without starting Kivy after initial setup.
android.entrypoint = com.local.ifunnyshare.ShareActivity
android.add_activities = org.kivy.android.PythonActivity
android.add_src = java
android.service_class_name = com.local.ifunnyshare.DownloadServiceBase
services = downloader:service.py:foreground:foregroundServiceType=dataSync
p4a.branch = develop
p4a.commit = 94ffd5f31d816414ad1fe66c0fe587c61daac757

[buildozer]
log_level = 2
warn_on_root = 1
