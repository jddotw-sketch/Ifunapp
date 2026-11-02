"""Small durable job registry. Each collection retains its own locked manifest."""
from contextlib import contextmanager
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import uuid


def now():
    return datetime.now(timezone.utc).isoformat()


class Jobs:
    def __init__(self, root, backups):
        self.root, self.backups = Path(root).resolve(), Path(backups).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.path = self.root / 'jobs.sqlite'
        with self.connect() as db:
            db.executescript('''PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS jobs(
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, source_key TEXT NOT NULL,
                    source_name TEXT NOT NULL, reader TEXT NOT NULL, scope TEXT NOT NULL,
                    state TEXT NOT NULL, stage TEXT NOT NULL, created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL, config TEXT NOT NULL, manifest TEXT NOT NULL,
                    output TEXT NOT NULL, error TEXT, pid INTEGER, result TEXT);
            ''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def add(self, source, name, scope, listing='popular', page_limit=3, legacy=None):
        if scope not in ('test', 'full') or not 1 <= page_limit <= 10:
            raise ValueError('Choose a full collection or a test of 1 to 10 pages')
        if not name.strip() or len(name) > 100:
            raise ValueError('Collection name must contain 1 to 100 characters')
        key = legacy.get('id') if legacy else uuid.uuid4().hex
        folder = self.root / 'collections' / key
        folder.mkdir(parents=True, exist_ok=True)
        params = {'source': source, 'listing': listing, 'page_limit': page_limit}
        if legacy:
            params['adapter_config'] = legacy['adapter_config']
        manifest = legacy['manifest'] if legacy else str(folder / 'manifest.sqlite')
        output = legacy['output'] if legacy else str(self.backups / key)
        stamp = legacy.get('created_at', now()) if legacy else now()
        with self.connect() as db:
            db.execute('INSERT INTO jobs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', (
                key, name.strip(), source['key'], source['name'], source['reader'], scope,
                'paused', 'ready', stamp, now(), json.dumps(params), manifest, output, None, None, None))
        return self.get(key)

    def get(self, key):
        with self.connect() as db:
            row = db.execute('SELECT * FROM jobs WHERE id=?', (key,)).fetchone()
        if row is None:
            raise KeyError('Collection not found')
        return dict(row)

    def all(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute('SELECT * FROM jobs ORDER BY created_at DESC')]

    def update(self, key, **values):
        allowed = {'state', 'stage', 'error', 'pid', 'result'}
        if not values or not set(values) <= allowed:
            raise ValueError('Invalid registry update')
        values['updated_at'] = now()
        with self.connect() as db:
            db.execute('UPDATE jobs SET ' + ','.join(f'{k}=?' for k in values) + ' WHERE id=?',
                       [*values.values(), key])

    def snapshot(self, job):
        result = {k: job[k] for k in ('id', 'name', 'source_name', 'source_key', 'reader', 'scope',
                                     'state', 'stage', 'created_at', 'updated_at', 'error')}
        result.update({'pages': {}, 'entries': 0, 'probes': 0, 'files': []})
        path = Path(job['manifest'])
        if path.exists():
            try:
                db = sqlite3.connect(f'file:{path}?mode=ro', uri=True, timeout=2)
                try:
                    result['pages'] = dict(db.execute('SELECT status,count(*) FROM pages GROUP BY status'))
                    result['entries'] = db.execute('SELECT count(*) FROM entries').fetchone()[0]
                    result['probes'] = db.execute('SELECT count(*) FROM probes').fetchone()[0]
                    done = db.execute("SELECT value FROM checkpoints WHERE key='discovered'").fetchone()
                    result['discovery_complete'] = bool(done and done[0] == 'yes')
                finally:
                    db.close()
            except sqlite3.Error:
                result['database_busy'] = True
        output = Path(job['output']).resolve()
        if output.is_relative_to(self.backups) and output.exists():
            # Only indexed, validated exports are offered for download.
            for index in output.glob('*-index.json'):
                try:
                    data = json.loads(index.read_text())
                    for entry in data['files']:
                        file = output / entry['file']
                        if file.resolve().parent != output or not file.is_file():
                            continue
                        result['files'].append({**entry, 'bytes': file.stat().st_size})
                except (ValueError, KeyError, OSError):
                    continue
        return result
