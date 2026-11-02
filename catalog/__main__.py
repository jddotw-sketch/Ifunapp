import argparse
import json

from .adapters import make_adapter
from .backup import export, validate
from .engine import Fetcher, ManifestLock, Store, collect, discover


def main():
    parser = argparse.ArgumentParser(description='Resumable adapter-based catalog pipeline')
    parser.add_argument('command', choices=['discover', 'collect', 'export', 'status', 'validate'])
    parser.add_argument('--config')
    parser.add_argument('--db', default='catalog.sqlite')
    parser.add_argument('--interval', type=float, default=1)
    parser.add_argument('--retries', type=int, default=5)
    parser.add_argument('--max-pages', type=int, default=1_000_000)
    parser.add_argument('--reader', choices=['anikku', 'komikku'])
    parser.add_argument('--output', default='backups')
    parser.add_argument('--allow-partial', action='store_true')
    parser.add_argument('--file')
    args = parser.parse_args()
    if args.command == 'validate':
        if not args.file or not args.reader:
            parser.error('validate requires --file and --reader')
        print(json.dumps({'entries': len(validate(args.file, args.reader).entries)}))
        return
    if not args.config or (args.command == 'export' and not args.reader):
        parser.error('--config is required; export also requires --reader')
    with open(args.config, encoding='utf-8') as f:
        adapter = make_adapter(json.load(f))
    with ManifestLock(args.db):
        store = Store(args.db, adapter)
        try:
            fetch = Fetcher(adapter, args.interval, args.retries)
            if args.command == 'discover':
                discover(store, adapter, fetch, args.max_pages)
            elif args.command == 'collect':
                collect(store, fetch)
            elif args.command == 'export':
                print(json.dumps(export(store, adapter, args.reader, args.output, args.allow_partial)))
            print(json.dumps(store.stats()))
        finally:
            store.close()


if __name__ == '__main__':
    main()
