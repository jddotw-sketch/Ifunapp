"""Upload validated complete exports on filesystem events, without a timer/cron.

Run separately from the collector, with read-only access to backups and a private
rclone configuration mount. Only indexed files are copied; nothing is made public.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess

from .backup import validate


class DriveUploads:
    def __init__(self, backups, credentials, status):
        self.backups = Path(backups).resolve()
        self.credentials, self.status = Path(credentials), Path(status)
        self.db = sqlite3.connect(self.credentials / 'uploads.sqlite')
        self.db.execute('CREATE TABLE IF NOT EXISTS uploads(path TEXT PRIMARY KEY,sha256 TEXT,url TEXT)')
        self.folder_url = None

    def command(self, *args):
        result = subprocess.run(['rclone', '--config', str(self.credentials / 'rclone.conf'),
                                 '--retries', '3', '--low-level-retries', '5', *map(str,args)],
                                capture_output=True, text=True, timeout=240)
        if result.returncode:
            # rclone stderr may contain OAuth/provider data; keep credentials out of UI/logs.
            raise RuntimeError(f'Drive transfer failed (exit {result.returncode}); local backups are preserved')
        return result.stdout

    def write_status(self, error=None):
        rows = self.db.execute('SELECT path,url FROM uploads ORDER BY path').fetchall()
        payload = {'status': 'upload_error' if error else 'connected', 'folder_url': self.folder_url,
                   'uploaded_files': len(rows), 'files': [{'file':p.rsplit('/',1)[-1],'url':u} for p,u in rows],
                   'error': error}
        temporary = self.status.with_suffix('.tmp')
        temporary.write_text(json.dumps(payload, indent=2)+'\n')
        temporary.chmod(0o644)
        temporary.replace(self.status)

    def sync_index(self, index):
        data = json.loads(index.read_text())
        if data.get('partial') is not False:
            return  # Samples and manually exported partial catalogs stay local.
        if data.get('reader') not in ('anikku','komikku') or not isinstance(data.get('files'),list):
            raise ValueError('Invalid export index')
        for item in data['files']:
            name = item['file']
            if not re.fullmatch(r'[A-Za-z0-9._-]+\.tachibk',name): raise ValueError('Invalid indexed filename')
            path = (index.parent / name).resolve()
            if not path.is_relative_to(self.backups) or path.parent != index.parent.resolve():
                raise ValueError('Indexed file leaves backup folder')
            raw = path.read_bytes()
            checksum = hashlib.sha256(raw).hexdigest()
            if checksum != item['sha256']: raise ValueError('Backup checksum mismatch; upload refused')
            previous = self.db.execute('SELECT sha256 FROM uploads WHERE path=?',(str(path),)).fetchone()
            if previous and previous[0] == checksum: continue
            validate(path, data['reader'], item['entries'])
            # Separate snapshots remain separate in Drive, even when an adapter ID is shared.
            parent = path.parent.name
            source = name.split('-' + data['reader'].capitalize() + '-',1)[0]
            folder = source + '-' + parent[:12]
            remote = 'catalogdrive:CatalogBackups/' + folder + '/' + name
            self.command('copyto',path,remote,'--immutable')
            remote_hash = self.command('md5sum',remote).split()[0]
            if remote_hash.lower() != hashlib.md5(raw).hexdigest():
                raise ValueError('Drive checksum verification failed')
            info = json.loads(self.command('lsjson',remote,'--stat'))
            url = 'https://drive.google.com/file/d/' + info['ID'] + '/view'
            with self.db:
                self.db.execute('INSERT OR REPLACE INTO uploads VALUES (?,?,?)',(str(path),checksum,url))
            self.write_status()

    def scan(self):
        try:
            if self.folder_url is None:
                info = json.loads(self.command('lsjson','catalogdrive:CatalogBackups','--stat'))
                self.folder_url = 'https://drive.google.com/drive/folders/' + info['ID']
            for index in sorted(self.backups.rglob('*-index.json')):
                self.sync_index(index)
            self.write_status()
        except Exception as exc:
            message = str(exc) if isinstance(exc,(ValueError,RuntimeError)) else 'Upload could not finish; local backups are preserved'
            self.write_status(message[:300])
            print(json.dumps({'status':'upload_error','error':message[:300]}),flush=True)

    def watch(self):
        libc = ctypes.CDLL(None,use_errno=True)
        fd = libc.inotify_init1(0)
        if fd < 0: raise OSError('Could not start export event listener')
        watched=set()
        def add_directories():
            for folder in [self.backups,*self.backups.rglob('*')]:
                if folder.is_dir() and folder not in watched:
                    if libc.inotify_add_watch(fd,os.fsencode(folder),0x8|0x80|0x100) < 0:
                        raise OSError('Could not watch backup directory')
                    watched.add(folder)
        try:
            add_directories()
            self.scan()
            while True:
                os.read(fd,65536)  # Blocks until an export creates/closes/moves a file.
                add_directories()
                self.scan()
        finally:
            os.close(fd)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--backups',default='/backups')
    parser.add_argument('--credentials',default='/credentials')
    parser.add_argument('--status',default='/status/cloud-status.json')
    parser.add_argument('--watch',action='store_true')
    args=parser.parse_args()
    os.umask(0o077)
    uploads=DriveUploads(args.backups,args.credentials,args.status)
    uploads.watch() if args.watch else uploads.scan()


if __name__=='__main__': main()
