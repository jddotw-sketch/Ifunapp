import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from catalog.backup import export
from catalog.drive_upload import DriveUploads
from catalog.engine import Entry, Page, Store
from catalog.tests.test_pipeline import Fake


class DriveTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.backups=self.root/'backups';self.backups.mkdir()
        credentials=self.root/'credentials';credentials.mkdir()
        self.uploads=DriveUploads(self.backups,credentials,self.root/'status.json')
        self.addCleanup(self.uploads.db.close)

    def seed(self, partial=False):
        folder=self.backups/'snapshot-one';folder.mkdir()
        store=Store(folder/'manifest.sqlite',Fake())
        try:
            store.add_page('1',1);store.commit_page('1',Page(True,(Entry('/entry/1','Example'),)))
            with store.db:store.put_checkpoint('discovered','yes')
            result=export(store,Fake(),'anikku',folder,allow_partial=partial)
        finally:store.close()
        index=next(folder.glob('*-index.json'))
        return index,result

    def test_samples_are_not_uploaded(self):
        index,_=self.seed(partial=True)
        self.uploads.command=lambda *_:self.fail('Partial samples must not contact Drive')
        self.uploads.sync_index(index)

    def test_checksum_mismatch_is_rejected_before_any_upload(self):
        index,result=self.seed()
        (index.parent/result['files'][0]['file']).write_bytes(b'corrupt')
        self.uploads.command=lambda *_:self.fail('Corrupt backups must not contact Drive')
        with self.assertRaises(ValueError):self.uploads.sync_index(index)

    def test_complete_backup_upload_is_checked_and_idempotent(self):
        index,result=self.seed()
        target=index.parent/result['files'][0]['file'];calls=[]
        def command(*args):
            calls.append(args)
            if args[0]=='copyto':return ''
            if args[0]=='md5sum':return hashlib.md5(target.read_bytes()).hexdigest()+'  file\n'
            if args[0]=='lsjson':return json.dumps({'ID':'private-file-id','Hashes':{'MD5':hashlib.md5(target.read_bytes()).hexdigest()}})
            self.fail('Unexpected command')
        self.uploads.command=command
        self.uploads.sync_index(index);self.uploads.sync_index(index)
        self.assertEqual(1,sum(c[0]=='copyto' for c in calls))
        copy=next(c for c in calls if c[0]=='copyto')
        self.assertIn('--immutable',copy)
        self.assertIn('Fixture-Anikku-Full-',copy[2])
        self.assertEqual(1,json.loads((self.root/'status.json').read_text())['uploaded_files'])

    def test_delayed_drive_index_retries_reads_without_uploading_twice(self):
        index,result=self.seed()
        target=index.parent/result['files'][0]['file'];reads=[];writes=[]
        def command(*args):
            if args[0]=='copyto':writes.append(args);return ''
            reads.append(args)
            if len(reads)<3:raise RuntimeError('Directory not found yet')
            return json.dumps({'ID':'private-id','Hashes':{'MD5':hashlib.md5(target.read_bytes()).hexdigest()}})
        self.uploads.command=command
        with patch('catalog.drive_upload.time.sleep'):
            self.uploads.sync_index(index)
        self.assertEqual(1,len(writes));self.assertEqual(3,len(reads))

    def test_index_cannot_upload_paths_outside_the_backup_folder(self):
        index,result=self.seed();result['files'][0]['file']='../private.tachibk'
        index.write_text(json.dumps(result))
        with self.assertRaises(ValueError):self.uploads.sync_index(index)
