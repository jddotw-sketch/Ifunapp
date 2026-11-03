"""Adapter for an existing, user-configured Suwayomi extension engine.

Source keys and IDs come from that engine, not from guessed website URLs. Listing
requests populate its metadata cache but never add entries to its library.
"""
import hashlib
from http.client import IncompleteRead, RemoteDisconnected
import json
import re
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .engine import Entry, Page, Retryable


class EngineAdapter:
    mode = 'sequential'
    first_key = '1'

    def __init__(self, config):
        self.config = config
        self.name = config['name']
        self.source = str(config['source_id'])
        if not self.source.lstrip('-').isdigit() or not -(2**63) <= int(self.source) < 2**63:
            raise ValueError('Invalid extension source ID')
        self.base = config['engine_url'].rstrip('/')
        self.listing = config.get('listing', 'popular')
        if self.listing not in ('popular', 'latest'):
            raise ValueError('Unsupported listing mode')
        self.reader_sources = {'komikku': int(self.source)}
        self.identity = hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()

    def fetch(self, key):
        if not str(key).isdigit() or int(key) < 1:
            raise ValueError('Invalid page number')
        url = f'{self.base}/api/v1/source/{self.source}/{self.listing}/{int(key)}'
        try:
            with urlopen(Request(url, headers={'Accept': 'application/json'}), timeout=90) as response:
                raw = response.read(16 * 1024 * 1024 + 1)
        except HTTPError as exc:
            # Suwayomi can wrap the website's HTTP 429 in an engine HTTP 500.
            # Inspect only a bounded response for the status; never expose its body,
            # which can contain URLs, headers or credentials, in dashboard errors.
            try:
                detail = exc.read(8192).decode('utf-8', errors='replace')
            except (OSError, IncompleteRead):
                detail = ''
            limited = exc.code == 429 or bool(re.search(r'HTTP(?: error| status)?[ :]+429\b', detail, re.I))
            context = f'{self.name}, page {key}'
            if limited:
                delay = 60
                value = (exc.headers or {}).get('Retry-After', '')
                if str(value).isdigit():
                    delay = min(300, max(delay, int(value)))
                raise Retryable(f'{context}: source rate limit; waiting before retrying', retry_after=delay) from exc
            if 500 <= exc.code < 600:
                # Older engines return only 'Server Error', hiding upstream rate limits.
                raise Retryable(f'{context}: extension engine returned HTTP {exc.code}', retry_after=60) from exc
            raise ValueError(f'{context}: extension engine returned HTTP {exc.code}') from exc
        except (URLError, TimeoutError, IncompleteRead, RemoteDisconnected, ConnectionError) as exc:
            raise Retryable('Extension request timed out or could not connect', retry_after=3) from exc
        if len(raw) > 16 * 1024 * 1024:
            raise ValueError('Extension listing exceeded the response limit')
        data = json.loads(raw)
        if not isinstance(data.get('mangaList'), list) or type(data.get('hasNextPage')) is not bool:
            raise ValueError('Extension did not return explicit listing/pagination data')
        entries = []
        for item in data['mangaList']:
            if str(item.get('sourceId')) != self.source:
                raise ValueError('Extension returned an entry belonging to another source')
            cover = item.get('thumbnailUrl') or ''
            # Engine-relative thumbnail proxies do not work on a phone away from the
            # engine. Let the installed extension fetch those covers on details refresh.
            if not cover.startswith(('https://', 'http://')):
                cover = ''
            entry = Entry(url=str(item.get('url') or ''), title=item.get('title') or '',
                author=item.get('author') or '', artist=item.get('artist') or '',
                description=item.get('description') or '', thumbnail=cover,
                genres=tuple(item.get('genre') or ()), status=0)
            entry.validate()
            entries.append(entry)
        if int(key) == 1 and not entries:
            raise ValueError('The first listing is empty. Check the extension before treating this as a catalog.')
        return Page(bool(entries), tuple(entries), str(int(key) + 1) if data['hasNextPage'] else None)
