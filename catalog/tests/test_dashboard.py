import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from catalog.backup import validate
from catalog.dashboard import Controller, handler
from catalog.dashboard_store import Jobs
from catalog.dashboard_worker import run
from catalog.engine import Retryable
from catalog.engine_api import EngineAdapter


class EngineResponse:
    def __init__(self, data):
        self.data = json.dumps(data).encode()
    def __enter__(self): return self
    def __exit__(self, *_): pass
    def read(self, _): return self.data


class EngineTests(unittest.TestCase):
    def adapter(self):
        return EngineAdapter({'name': 'Example', 'source_id': '918523496924275699', 'engine_url': 'http://engine:4567'})

    def payload(self):
        return {'mangaList': [{'sourceId': '918523496924275699', 'url': '1317', 'title': 'Example',
                               'thumbnailUrl': '/api/v1/manga/12/thumbnail'}], 'hasNextPage': True}

    def test_uses_canonical_extension_key_and_drops_unportable_cover_proxy(self):
        with patch('catalog.engine_api.urlopen', return_value=EngineResponse(self.payload())):
            page = self.adapter().fetch('1')
        self.assertEqual('1317', page.entries[0].url)
        self.assertEqual('', page.entries[0].thumbnail)
        self.assertEqual('2', page.next_key)

    def test_foreign_source_is_rejected(self):
        data = self.payload()
        data['mangaList'][0]['sourceId'] = '8'
        with patch('catalog.engine_api.urlopen', return_value=EngineResponse(data)):
            with self.assertRaises(ValueError): self.adapter().fetch('1')

    def test_missing_pagination_and_empty_first_page_are_not_end_signals(self):
        for data in ({'mangaList': []}, {'mangaList': [], 'hasNextPage': False}):
            with patch('catalog.engine_api.urlopen', return_value=EngineResponse(data)):
                with self.assertRaises(ValueError): self.adapter().fetch('1')

    def test_http_failure_never_becomes_terminal_page(self):
        for status in (404, 429, 502):
            with patch('catalog.engine_api.urlopen', side_effect=HTTPError('url', status, 'error', {}, None)):
                with self.assertRaises(Retryable if status in (429, 502) else ValueError): self.adapter().fetch('1')


class DashboardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sources = self.root / 'sources.json'
        self.source = {'key': 'example', 'name': 'Example source', 'reader': 'komikku', 'kind': 'engine',
                       'source_id': '7', 'engine_url': 'http://127.0.0.1:1', 'ready': True}
        self.sources.write_text(json.dumps({'sources': [self.source], 'note': 'Fixture'}))
        self.controller = Controller(self.root / 'registry', self.root / 'backups', self.sources, 'http://dashboard')

    def test_restart_preserves_job_and_requires_manual_resume(self):
        job = self.controller.jobs.add(self.source, 'My test', 'test')
        self.controller.jobs.update(job['id'], state='running', stage='collecting', pid=999999)
        again = Controller(self.root / 'registry', self.root / 'backups', self.sources, 'http://dashboard')
        self.assertEqual('interrupted', again.jobs.get(job['id'])['state'])
        self.assertEqual(job['manifest'], again.jobs.get(job['id'])['manifest'])

    def test_collection_limit_is_enforced_before_creating_another(self):
        for i in range(2):
            job = self.controller.jobs.add(self.source, str(i), 'full')
            self.controller.jobs.update(job['id'], state='running')
        with self.assertRaises(ValueError): self.controller.create({'source_key': 'example', 'name': 'Third'})
        self.assertEqual(2, len(self.controller.jobs.all()))

    def test_unknown_source_and_download_traversal_rejected(self):
        with self.assertRaises(ValueError): self.controller.source('http://arbitrary-server')
        with self.assertRaises(ValueError): self.controller.download('unknown', '../../private.tachibk')

    def test_worker_launch_failure_is_resumable(self):
        job = self.controller.jobs.add(self.source, 'Launch failure', 'test')
        with patch('catalog.dashboard.subprocess.Popen', side_effect=OSError('fixture')):
            with self.assertRaises(ValueError): self.controller.start(job['id'])
        self.assertEqual('failed', self.controller.jobs.get(job['id'])['state'])

    def test_http_page_health_and_cross_site_action_protection(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler(self.controller))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f'http://127.0.0.1:{server.server_port}'
            with urlopen(base + '/') as response:
                self.assertIn(b'Start a collection', response.read())
            with urlopen(base + '/api/health') as response:
                self.assertEqual('ok', json.load(response)['status'])
            request = Request(base + '/api/jobs', data=b'{}', headers={'Content-Type': 'application/json', 'Origin': 'https://other-site'})
            with self.assertRaises(HTTPError) as error: urlopen(request)
            self.assertEqual(403, error.exception.code)
            self.assertEqual([], self.controller.jobs.all())
        finally:
            server.shutdown()
            server.server_close()

    def test_bounded_worker_exports_partial_backup_and_replays_cached_pages(self):
        class FakeEngine(BaseHTTPRequestHandler):
            calls = []
            def do_GET(self):
                n = int(self.path.rsplit('/', 1)[1])
                self.calls.append(n)
                body = json.dumps({'mangaList': [{'sourceId': '7', 'url': f'/g/{n}', 'title': f'Example {n}'}], 'hasNextPage': True}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(body)
            def log_message(self, *_): pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), FakeEngine)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            source = {**self.source, 'engine_url': f'http://127.0.0.1:{server.server_port}'}
            job = self.controller.jobs.add(source, 'Bounded sample', 'test', page_limit=3)
            run(self.controller.jobs, job['id'])
            updated = self.controller.jobs.get(job['id'])
            self.assertEqual('completed', updated['state'])
            result = json.loads(updated['result'])
            self.assertTrue(result['partial'])
            self.assertEqual(3, result['entries'])
            file = Path(updated['output']) / result['files'][0]['file']
            self.assertEqual(3, len(validate(file, 'komikku').entries))
            run(self.controller.jobs, job['id'])
            self.assertEqual([1, 2, 3], FakeEngine.calls)
            self.assertEqual(3, self.controller.jobs.snapshot(self.controller.jobs.get(job['id']))['entries'])
        finally:
            server.shutdown(); server.server_close()
