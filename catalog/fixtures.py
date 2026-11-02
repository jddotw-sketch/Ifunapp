"""Generate synthetic interoperability/scale artifacts; never contacts a source."""
import argparse
import json
from pathlib import Path

from .backup import export
from .engine import Store
from .tests.test_pipeline import Fake


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--count', type=int, default=5001)
    args = parser.parse_args()
    root = Path(args.output)
    root.mkdir(parents=True, exist_ok=True)
    adapter = Fake()
    store = Store(root / 'fixture.sqlite', adapter)
    try:
        with store.db:
            store.db.executemany('INSERT OR IGNORE INTO entries(url,payload) VALUES (?,?)', (
                (f'/entry/{i}', json.dumps({'url': f'/entry/{i}', 'title': f'Title {i} 🐈',
                 'author': '', 'artist': '', 'description': '', 'thumbnail': '', 'genres': [], 'status': 0}))
                for i in range(args.count)))
            store.put_checkpoint('discovered', 'yes')
        for reader in ('anikku', 'komikku'):
            result = export(store, adapter, reader, root / reader)
            assert result['entries'] == args.count
            expected = [50_000] * (args.count // 50_000) + ([args.count % 50_000] if args.count % 50_000 else [])
            assert [f['entries'] for f in result['files']] == expected
            print(json.dumps(result))
    finally:
        store.close()


if __name__ == '__main__':
    main()
