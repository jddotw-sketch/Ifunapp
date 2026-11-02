"""LAN dashboard. Workers have no Docker socket or arbitrary command/URL input."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
from urllib.parse import unquote, urlsplit

from .dashboard_store import Jobs

ACTIVE = ('starting', 'running', 'pausing')


class Controller:
    def __init__(self, root, backups, sources_path, origin, max_active=2):
        self.jobs = Jobs(root, backups)
        self.sources_path = Path(sources_path)
        self.origin, self.max_active = origin, max_active
        self.lock = threading.RLock()
        self.processes = {}
        for job in self.jobs.all():
            if job['state'] in ACTIVE:
                self.jobs.update(job['id'], state='interrupted', stage='interrupted', pid=None,
                                 error='The dashboard restarted. Resume when you are ready.')

    def sources(self):
        data = json.loads(self.sources_path.read_text(encoding='utf-8'))
        passed = {j['source_key'] for j in self.jobs.all() if j['scope'] == 'test' and j['state'] == 'completed'}
        for source in data['sources']:
            source['tested'] = source.get('tested', False) or source['key'] in passed
        return data

    def source(self, key):
        source = next((s for s in self.sources()['sources'] if s['key'] == key), None)
        if source is None or not source.get('ready'):
            raise ValueError('This source needs setup before it can collect a catalog.')
        return source

    def start(self, key):
        with self.lock:
            job = self.jobs.get(key)
            if job['state'] in ACTIVE:
                raise ValueError('This collection is already running.')
            if job['state'] == 'completed':
                raise ValueError('This collection is finished. Start a new snapshot instead.')
            active = sum(j['state'] in ACTIVE for j in self.jobs.all())
            if active >= self.max_active:
                raise ValueError(f'{self.max_active} collections are already running. Pause one first.')
            folder = self.jobs.root / 'collections' / key
            folder.mkdir(parents=True, exist_ok=True)
            self.jobs.update(key, state='starting', stage='preparing', error=None)
            try:
                with (folder / 'run.log').open('ab') as log:
                    process = subprocess.Popen([sys.executable, '-u', '-m', 'catalog.dashboard_worker',
                        '--root', str(self.jobs.root), '--backups', str(self.jobs.backups), '--job', key],
                        stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
            except OSError as exc:
                self.jobs.update(key, state='failed', stage='failed', pid=None, error='The worker could not start.')
                raise ValueError('The worker could not start. Your collection is saved; try Resume.') from exc
            self.processes[key] = process
            self.jobs.update(key, pid=process.pid)
            def finished():
                code = process.wait()
                with self.lock:
                    latest = self.jobs.get(key)
                    if latest['state'] == 'pausing':
                        self.jobs.update(key, state='paused', stage='paused', pid=None, error=None)
                    elif latest['state'] in ACTIVE:
                        self.jobs.update(key, state='failed', stage='failed', pid=None,
                                         error=f'The worker stopped (exit {code}). Your checkpoint is saved; resume to retry.')
                    self.processes.pop(key, None)
            threading.Thread(target=finished, daemon=True).start()
            return self.jobs.snapshot(self.jobs.get(key))

    def create(self, data):
        source = self.source(data.get('source_key'))
        scope = data.get('scope', 'test')
        listing = data.get('listing', 'popular')
        if listing not in ('popular', 'latest'):
            raise ValueError('Choose Popular or Latest.')
        if listing not in source.get('listings', ('popular', 'latest')):
            raise ValueError('This listing is not supported by the source adapter.')
        limit = data.get('page_limit', 3)
        if type(limit) is not int:
            raise ValueError('Test page count must be a whole number.')
        with self.lock:
            if sum(j['state'] in ACTIVE for j in self.jobs.all()) >= self.max_active:
                raise ValueError(f'{self.max_active} collections are already running. Pause one first.')
            job = self.jobs.add(source, data.get('name') or source['name'], scope, listing, limit)
            return self.start(job['id'])

    def pause(self, key):
        with self.lock:
            process = self.processes.get(key)
            if process is None or process.poll() is not None:
                raise ValueError('This collection is not running in the dashboard.')
            self.jobs.update(key, state='pausing', stage='pausing')
            process.terminate()
        return self.jobs.snapshot(self.jobs.get(key))

    def download(self, key, name):
        if not re.fullmatch(r'[A-Za-z0-9._-]+\.tachibk', name):
            raise ValueError('Invalid backup filename')
        job = self.jobs.get(key)
        snapshot = self.jobs.snapshot(job)
        entry = next((f for f in snapshot['files'] if name in (f['file'], f.get('legacy_file'))), None)
        if entry is None:
            raise KeyError('Validated backup not found')
        output = Path(job['output']).resolve()
        path = (output / entry['file']).resolve()
        if not output.is_relative_to(self.jobs.backups) or path.parent != output:
            raise ValueError('Invalid backup path')
        return path


def handler(controller):
    static = Path(__file__).with_name('web')
    class Handler(BaseHTTPRequestHandler):
        server_version = 'CatalogDashboard'

        def response_headers(self, code, content_type, length):
            self.send_response(code)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(length))
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'same-origin')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")

        def json(self, code, data):
            raw = json.dumps(data).encode()
            self.response_headers(code, 'application/json; charset=utf-8', len(raw))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            path = unquote(urlsplit(self.path).path)
            try:
                if path == '/api/health':
                    return self.json(200, {'status': 'ok'})
                if path == '/api/sources':
                    data = controller.sources()
                    # Internal engine URLs are server configuration, not UI input.
                    for source in data['sources']:
                        source.pop('engine_url', None)
                    return self.json(200, data)
                if path == '/api/jobs':
                    return self.json(200, {'jobs': [controller.jobs.snapshot(j) for j in controller.jobs.all()]})
                if path == '/api/cloud':
                    status = controller.jobs.root.parent / 'cloud-public/cloud-status.json'
                    return self.json(200, json.loads(status.read_text()) if status.exists() else {'status': 'not_configured'})
                match = re.fullmatch(r'/api/jobs/([a-z0-9-]+)/files/(.+)', path)
                if match:
                    file = controller.download(match[1], match[2])
                    self.response_headers(200, 'application/octet-stream', file.stat().st_size)
                    self.send_header('Content-Disposition', f'attachment; filename="{file.name}"')
                    self.end_headers()
                    with file.open('rb') as stream:
                        while block := stream.read(1024 * 1024):
                            self.wfile.write(block)
                    return
                files = {'/': 'index.html', '/app.js': 'app.js', '/style.css': 'style.css'}
                if path not in files:
                    return self.json(404, {'error': 'Page not found'})
                file = static / files[path]
                raw = file.read_bytes()
                self.response_headers(200, mimetypes.guess_type(file.name)[0] or 'text/plain', len(raw))
                self.end_headers()
                self.wfile.write(raw)
            except (ValueError, KeyError) as exc:
                self.json(404, {'error': str(exc)})
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_POST(self):
            try:
                if self.headers.get('Origin') != controller.origin:
                    return self.json(403, {'error': 'Open the dashboard page to perform this action.'})
                if self.headers.get_content_type() != 'application/json':
                    return self.json(415, {'error': 'Expected JSON'})
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 8192:
                    raise ValueError('Invalid request size')
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict):
                    raise ValueError('Invalid request')
                path = urlsplit(self.path).path
                if path == '/api/jobs':
                    return self.json(201, controller.create(data))
                match = re.fullmatch(r'/api/jobs/([a-z0-9-]+)/(pause|resume)', path)
                if match:
                    result = controller.pause(match[1]) if match[2] == 'pause' else controller.start(match[1])
                    return self.json(200, result)
                self.json(404, {'error': 'Action not found'})
            except (ValueError, KeyError) as exc:
                self.json(400, {'error': str(exc)})
            except Exception:
                self.json(500, {'error': 'The action could not finish. Refresh and check the collection status.'})

        def log_message(self, *_):
            pass
    return Handler


def main():
    os.umask(0o002)
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', default='/data/dashboard')
    parser.add_argument('--backups', default='/data/backups')
    parser.add_argument('--sources', default='/data/dashboard/sources.json')
    parser.add_argument('--listen', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8087)
    parser.add_argument('--origin', default='http://192.168.12.237:8087')
    parser.add_argument('--max-active', type=int, choices=range(1, 5), default=2)
    args = parser.parse_args()
    controller = Controller(args.root, args.backups, args.sources, args.origin, max_active=args.max_active)
    server = ThreadingHTTPServer((args.listen, args.port), handler(controller))
    print(f'Catalog dashboard listening on port {args.port}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
