"""Apply small build/branding changes to the pinned upstream after copying the overlay."""
from pathlib import Path
import os
import sys
import xml.etree.ElementTree as ET

port = Path(__file__).resolve().parent
target = Path(sys.argv[1]).resolve()

def merge_strings(destination, additions):
    root = ET.parse(destination).getroot()
    names = {node.attrib.get('name') for node in root}
    for node in ET.parse(additions).getroot():
        if node.attrib.get('name') not in names:
            root.append(node)
    ET.ElementTree(root).write(destination, encoding='utf-8', xml_declaration=True)

# Start from upstream resources, then merge importer additions as before.
merge_strings(target / 'i18n-kmk/src/commonMain/moko-resources/base/strings.xml',
              port / 'overlay/i18n-kmk/src/commonMain/moko-resources/base/strings.xml')
merge_strings(target / 'i18n-ank/src/commonMain/moko-resources/base/strings.xml',
              port / 'strings-alldebrid.xml')

manifest = target / 'app/src/main/AndroidManifest.xml'
text = manifest.read_text(encoding='utf-8')
text = text.replace('android:label="@string/app_name"', 'android:label="@string/library_import_app_name"')
# Existing users may already have an alternative launcher alias selected.
text = text.replace('android:label="Anikun"', 'android:label="@string/library_import_app_name"')
text = text.replace('android:label="Onigiri"', 'android:label="@string/library_import_app_name"')
manifest.write_text(text, encoding='utf-8')

build = target / 'app/build.gradle.kts'
text = build.read_text(encoding='utf-8')
assert 'versionCode = 8' in text, 'Pinned upstream version has changed; inspect before applying.'
text = text.replace('versionCode = 8', 'versionCode = providers.environmentVariable("ANIKKU_VERSION_CODE").orElse("1000000").get().toInt()')
# Keep app.anikku.dev, the identity used by all prior importer debug APKs.
text += '''
// ANK: Stable importer signing is opt-in through CI secrets. Never commit a keystore.
android {
    val importerKeystore = providers.environmentVariable("ANIKKU_KEYSTORE_PATH").orNull
    if (!importerKeystore.isNullOrBlank()) {
        signingConfigs.create("libraryImport") {
            storeFile = file(importerKeystore)
            storePassword = providers.environmentVariable("ANIKKU_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANIKKU_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANIKKU_KEY_PASSWORD").get()
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("libraryImport")
    }
}
'''
build.write_text(text, encoding='utf-8')

# Android 26+ uses adaptive icons. Add a high-contrast import badge to every existing variant.
badge = '''<path android:fillColor="#083344" android:pathData="M61,60h30v30h-30z" />
    <path android:fillColor="#67E8F9" android:pathData="M73,64h6v11h6l-9,9 -9,-9h6zM67,85h18v3h-18z" />'''
res = target / 'app/src/main/res'
for adaptive in (res / 'mipmap-anydpi-v26').glob('ic_launcher*.xml'):
    root = ET.parse(adaptive).getroot()
    ns = '{http://schemas.android.com/apk/res/android}'
    foreground = root.find('foreground')
    if foreground is None:
        continue
    original = foreground.attrib[ns + 'drawable']
    # Wrap the existing foreground asset in an inset layer, preserving Anikku artwork.
    name = adaptive.stem + '_library_import'
    vector = res / 'drawable' / 'library_import_badge.xml'
    vector.write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    ''' + badge + '\n</vector>\n', encoding='utf-8')
    (res / 'drawable' / (name + '.xml')).write_text('''<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="''' + original + '''" />
    <item android:drawable="@drawable/library_import_badge" />
</layer-list>\n''', encoding='utf-8')
    foreground.set(ns + 'drawable', '@drawable/' + name)
    # Use the same badge on themed launcher icons.
    monochrome = root.find('monochrome')
    if monochrome is not None:
        monochrome.set(ns + 'drawable', '@drawable/library_import_badge')
    ET.register_namespace('android', ns[1:-1])
    ET.ElementTree(root).write(adaptive, encoding='utf-8', xml_declaration=True)

print('Importer overlay, AllDebrid resources, launcher branding and update configuration applied.')
