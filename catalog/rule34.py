"""Rule34Video server integration for the pinned external catalog pipeline.

The source publishes a Last-page link. Values above that verified snapshot boundary
are absent by source metadata, not by interpreting HTTP errors as an empty catalog.
All pages inside the boundary must have the expected root, active page and entries.
Canonical URLs, title and thumbnail rules follow the Yuzono Rule34Video extension.
"""
import argparse
from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import time

from bs4 import BeautifulSoup
from catalog.adapters import HtmlAdapter
from catalog.backup import export
from catalog.engine import Fetcher, ManifestLock, Page, Store, collect, discover

DATA = Path('/data')
CONFIG = DATA / 'config/rule34video.json'
DB = DATA / 'state/rule34video.sqlite'

BASE_CONFIG = {
    'name': 'Rule34Video', 'adapter': 'html', 'media_type': 'video',
    'mode': 'monotonic', 'first_key': '1',
    'base_url': 'https://rule34video.com',
    'url_template': 'https://rule34video.com/latest-updates/{key}/',
    'reader_sources': {'anikku': 9212790518101043860},
    'thumbnail_attribute': 'data-rd-jpg', 'timeout': 45,
    'source_adapter': 'rule34video-published-boundary-v1',
    'selectors': {
        'catalog': '#custom_list_videos_latest_videos_list',
        'entry': '#custom_list_videos_latest_videos_list_items div.item.thumb:has(a.th[href*="/video"])',
        'link': 'a.th', 'title': 'div.thumb_title', 'thumbnail': 'div.img img',
        # Empty listings inside the published boundary are errors, never terminal pages.
        'empty': '#catalog-empty-is-not-a-terminal-signal',
    },
}


class Rule34Adapter(HtmlAdapter):
    def __init__(self, config):
        super().__init__(config)
        self.last_page = config['snapshot_last_page']
        if type(self.last_page) is not int or not 1 <= self.last_page <= 1_000_000:
            raise ValueError('Invalid published page boundary')

    def read(self, key):
        raw = super().read(key)
        self.validate_active(raw, int(key))
        return raw

    @staticmethod
    def validate_active(raw, expected):
        document = BeautifulSoup(raw, 'html.parser')
        active = document.select_one('#custom_list_videos_latest_videos_list_pagination .item.active a')
        if active is None or not active.get_text(strip=True).isdigit() or int(active.get_text(strip=True)) != expected:
            raise ValueError('Source returned a different page, challenge or changed pagination')

    def fetch(self, key):
        if not str(key).isdigit() or int(key) < 1:
            raise ValueError('Rule34Video requires positive numbered pages')
        if int(key) > self.last_page:
            return Page(False)  # Explicit published metadata; no HTTP error is swallowed.
        page = super().fetch(key)
        if not page.exists or not page.entries:
            raise ValueError('A page inside the published snapshot boundary is empty')
        return page


def atomic_json(path, data):
    path = Path(path)
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    temp.replace(path)


def bootstrap():
    if CONFIG.exists():
        raise ValueError('Existing source snapshot is preserved; use the existing configuration')
    plain = HtmlAdapter(BASE_CONFIG)
    raw = plain.read('1')
    Rule34Adapter.validate_active(raw, 1)
    document = BeautifulSoup(raw, 'html.parser')
    last = [a for a in document.select('#custom_list_videos_latest_videos_list_pagination a[href]')
            if a.get_text(strip=True).lower() == 'last']
    if len(last) != 1:
        raise ValueError('Missing or ambiguous published Last-page link')
    match = re.fullmatch(r'/latest-updates/(\d+)/', last[0]['href'])
    if not match:
        raise ValueError('Unexpected Last-page URL')
    config = {**BASE_CONFIG, 'snapshot_last_page': int(match[1])}
    adapter = Rule34Adapter(config)
    fetch = Fetcher(adapter, interval=1, retries=3)
    first = fetch('1')
    final = fetch(str(adapter.last_page))
    if not final.exists or not final.entries:
        raise ValueError('Published final page could not be verified')
    # Protect bootstrap with the same lock used by discovery and collection.
    with ManifestLock(DB):
        if DB.exists():
            raise ValueError('A manifest already exists; refusing to replace its source snapshot')
        store = Store(DB, adapter)
        try:
            with store.db:
                for key, page in (('1', first), (str(adapter.last_page), final)):
                    store.db.execute('INSERT INTO probes VALUES (?,?)', (key, json.dumps(asdict(page))))
            atomic_json(CONFIG, config)
        finally:
            store.close()
    evidence = {
        'checked_at_utc': datetime.now(timezone.utc).isoformat(),
        'source_url': BASE_CONFIG['url_template'],
        'reported_last_page': adapter.last_page,
        'first_page_entries': len(first.entries), 'last_page_entries': len(final.entries),
        'listing_sha256': hashlib.sha256(raw).hexdigest(),
        'extension_index': 'https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json',
        'extension_package': 'eu.kanade.tachiyomi.animeextension.en.rule34video',
        'extension_version_checked': '16.11',
        'extension_source_id': BASE_CONFIG['reader_sources']['anikku'],
        'parser_reference': 'https://github.com/yuzono/anime-extensions/blob/master/src/en/rule34video/src/eu/kanade/tachiyomi/animeextension/en/rule34video/Rule34Video.kt',
    }
    atomic_json(DATA / 'validation/rule34video-source-evidence.json', evidence)
    print(json.dumps(evidence), flush=True)


class ProgressStore(Store):
    def __init__(self, *args):
        super().__init__(*args)
        self.completed = self.db.execute("SELECT count(*) FROM pages WHERE status='done'").fetchone()[0]
        self.start = time.monotonic()

    def commit_page(self, key, page):
        super().commit_page(key, page)
        self.completed += 1
        if self.completed % 100 == 0:
            print(json.dumps({'stage': 'collect', **self.stats(), 'elapsed_seconds': round(time.monotonic() - self.start)}), flush=True)


def main():
    os.umask(0o002)
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['bootstrap', 'run', 'discover', 'collect', 'export', 'status'])
    args = parser.parse_args()
    if args.command == 'bootstrap':
        bootstrap()
        return
    config = json.loads(CONFIG.read_text(encoding='utf-8'))
    adapter = Rule34Adapter(config)
    if args.command == 'status':
        connection = sqlite3.connect(f'file:{DB}?mode=ro', uri=True)
        try:
            pages = dict(connection.execute('SELECT status,count(*) FROM pages GROUP BY status'))
            entries = connection.execute('SELECT count(*) FROM entries').fetchone()[0]
            print(json.dumps({'pages': pages, 'entries': entries,
                              'snapshot_pages': adapter.last_page}), flush=True)
        finally:
            connection.close()
        return
    with ManifestLock(DB):
        store = ProgressStore(DB, adapter)
        try:
            fetch = Fetcher(adapter, interval=1, retries=5)
            if args.command in ('run', 'discover'):
                discover(store, adapter, fetch, max_pages=100_000)
                print(json.dumps({'stage': 'discovered', **store.stats()}), flush=True)
            if args.command in ('run', 'collect'):
                collect(store, fetch, workers=2)
                print(json.dumps({'stage': 'collected', **store.stats()}), flush=True)
            if args.command in ('run', 'export'):
                result = export(store, adapter, 'anikku', DATA / 'backups/Rule34Video')
                print(json.dumps({'stage': 'exported', **result}), flush=True)
            if args.command == 'status':
                print(json.dumps(store.stats()), flush=True)
        finally:
            store.close()


if __name__ == '__main__':
    main()
