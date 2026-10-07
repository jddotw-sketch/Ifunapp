"""Fail the CI build if share registration or foreground-service permissions are missing."""
from pathlib import Path
import argparse
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'


def verify(path):
    root = ET.parse(path).getroot()
    app = root.find('application')
    if app is None:
        raise ValueError('No application element')
    activities = {a.get(ANDROID+'name'): a for a in app.findall('activity')}
    receiver = activities.get('com.local.ifunnyshare.ShareActivity')
    if receiver is None or receiver.get(ANDROID+'exported') != 'true':
        raise ValueError('Exported native share receiver missing')
    matched = False
    for f in receiver.findall('intent-filter'):
        actions = {x.get(ANDROID+'name') for x in f.findall('action')}
        categories = {x.get(ANDROID+'name') for x in f.findall('category')}
        types = {x.get(ANDROID+'mimeType') for x in f.findall('data')}
        if ('android.intent.action.SEND' in actions and
            'android.intent.category.DEFAULT' in categories and 'text/*' in types):
            matched = True
    if not matched:
        raise ValueError('Share SEND/text filter missing')
    if 'org.kivy.android.PythonActivity' not in activities:
        raise ValueError('Python setup activity missing')
    services = {s.get(ANDROID+'name'): s for s in app.findall('service')}
    service = services.get('com.local.ifunnyshare.ServiceDownloader')
    if service is None or service.get(ANDROID+'foregroundServiceType') != 'dataSync':
        raise ValueError('dataSync download service missing')
    if not (service.get(ANDROID+'process') or '').startswith(':'):
        raise ValueError('Download service must run in a separate process')
    permissions = {p.get(ANDROID+'name') for p in root.findall('uses-permission')}
    required = {'android.permission.'+p for p in ('INTERNET','WRITE_EXTERNAL_STORAGE',
                'POST_NOTIFICATIONS','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','WAKE_LOCK')}
    if not required <= permissions:
        raise ValueError('Missing permissions: '+str(required-permissions))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-root', default='.buildozer')
    args = parser.parse_args()
    for path in Path(args.build_root).rglob('AndroidManifest.xml'):
        try:
            verify(path)
        except (ValueError, ET.ParseError):
            continue
        print('Verified share receiver, setup activity, download service and permissions:', path)
        return
    raise SystemExit('No generated app manifest passed the background-download checks.')


if __name__ == '__main__':
    main()
