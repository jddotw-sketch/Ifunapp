import argparse
from dataclasses import asdict
import json
import os
from pathlib import Path
import signal
import traceback

from bs4 import BeautifulSoup
from .adapters import HtmlAdapter
from .backup import export
from .dashboard_store import Jobs
from .engine import Fetcher, ManifestLock, Store, _page_decode, collect, discover
from .engine_api import EngineAdapter
from .rule34 import BASE_CONFIG, Rule34Adapter
import re


def make_adapter(source, config):
    return Rule34Adapter(config) if source['kind'] == 'rule34' else EngineAdapter(config)


def prepare_rule34():
    plain = HtmlAdapter(BASE_CONFIG)
    raw = plain.read('1')
    Rule34Adapter.validate_active(raw, 1)
    document = BeautifulSoup(raw, 'html.parser')
    anchors = [a for a in document.select('#custom_list_videos_latest_videos_list_pagination a[href]')
               if a.get_text(strip=True).lower() == 'last']
    if len(anchors) != 1:
        raise ValueError('The source did not publish an unambiguous Last-page link')
    match = re.fullmatch(r'/latest-updates/(\d+)/', anchors[0]['href'])
    if not match:
        raise ValueError('Unexpected last-page link')
    return {**BASE_CONFIG, 'snapshot_last_page': int(match[1])}


def run(registry, key):
    os.umask(0o002)
    job = registry.get(key)
    params = json.loads(job['config'])
    source = params['source']
    config_path = Path(job['manifest']).with_suffix('.config.json')
    store = None
    try:
        registry.update(key, state='running', stage='preparing', pid=os.getpid(), error=None)
        if config_path.exists():
            config = json.loads(config_path.read_text())
        elif 'adapter_config' in params:
            config = params['adapter_config']
        elif source['kind'] == 'rule34':
            config = prepare_rule34()
        else:
            config = {'name': source['name'], 'engine_url': source['engine_url'],
                      'source_id': source['source_id'], 'listing': params['listing']}
        if not config_path.exists():
            config_path.write_text(json.dumps(config, indent=2) + '\n')
        adapter = make_adapter(source, config)
        fetch = Fetcher(adapter, interval=1.0, retries=5)
        with ManifestLock(job['manifest']):
            store = Store(job['manifest'], adapter)
            registry.update(key, stage='discovering')
            if job['scope'] == 'full':
                discover(store, adapter, fetch, max_pages=100_000)
            else:
                # Explicitly bounded samples retain an incomplete discovery checkpoint;
                # their indexes are always marked partial, never presented as full catalogs.
                cursor = adapter.first_key
                for ordinal in range(1, params['page_limit'] + 1):
                    row = store.db.execute('SELECT payload,next_key FROM pages WHERE key=?', (cursor,)).fetchone()
                    page = _page_decode(row[0]) if row and row[0] else fetch(cursor)
                    with store.db:
                        store.db.execute('INSERT OR IGNORE INTO pages(key,ordinal,payload,next_key) VALUES (?,?,?,?)',
                            (cursor, ordinal, json.dumps(asdict(page)), page.next_key))
                    if adapter.mode == 'monotonic':
                        if not page.exists:
                            break
                        cursor = str(int(cursor) + 1)
                    else:
                        if page.next_key is None:
                            break
                        cursor = page.next_key
            registry.update(key, stage='collecting')
            collect(store, fetch, workers=2 if adapter.mode == 'monotonic' else 1)
            registry.update(key, stage='exporting')
            result = export(store, adapter, job['reader'], job['output'], allow_partial=job['scope'] == 'test')
            registry.update(key, state='completed', stage='completed', pid=None,
                            result=json.dumps(result), error=None)
            print(json.dumps({'state': 'completed', 'entries': result['entries'], 'files': len(result['files'])}), flush=True)
    except KeyboardInterrupt:
        registry.update(key, state='paused', stage='paused', pid=None, error=None)
    except BaseException as exc:
        registry.update(key, state='failed', stage='failed', pid=None, error=str(exc)[:1000])
        traceback.print_exc()
        raise
    finally:
        if store is not None:
            store.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True)
    parser.add_argument('--backups', required=True)
    parser.add_argument('--job', required=True)
    args = parser.parse_args()
    def pause(*_):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, pause)
    run(Jobs(args.root, args.backups), args.job)


if __name__ == '__main__':
    main()
