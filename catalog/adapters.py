"""Normalized JSON transport adapter. Native websites need their own parser adapter."""
import hashlib
import json
import socket
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen
from urllib.parse import urljoin, urlsplit

from .engine import Entry, Page, Retryable


class JsonAdapter:
    """Strict contract: {exists: bool, entries: [...], next_key: string|null}.

    URL must be the extension's reader-relative canonical key, not an invented URL.
    Only explicit exists=false is terminal for numbered sources. 404 is an error.
    Sequential sources must supply next_key even across empty/gapped pages.
    """
    def __init__(self, config):
        self.config = config
        self.name = config['name']
        self.mode = config['mode']
        self.first_key = str(config.get('first_key', '1'))
        self.reader_sources = config['reader_sources']
        if not self.reader_sources or any(r not in ('anikku', 'komikku') or
                type(s) is not int or not -(2**63) <= s < 2**63 for r, s in self.reader_sources.items()):
            raise ValueError("Explicit signed 64-bit reader source IDs are required")
        allowed = {'video': {'anikku'}, 'manga': {'komikku'}}.get(config.get('media_type'))
        if allowed is None or not set(self.reader_sources) <= allowed:
            raise ValueError('media_type must match the reader: video/anikku or manga/komikku')
        self.identity = hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()

    def read(self, key):
        if 'fixture_dir' in self.config:
            # Prevent cursor strings from escaping the fixture directory.
            raw = (Path(self.config['fixture_dir']) / (quote(key, safe='') + '.json')).read_bytes()
        else:
            url = self.config['url_template'].replace('{key}', quote(key, safe=''))
            if not url.startswith('https://'):
                raise ValueError("HTTP adapters require HTTPS")
            try:
                request = Request(url, headers={'User-Agent': 'Ifunapp-Catalog/1.0', 'Accept': 'application/json'})
                with urlopen(request, timeout=self.config.get('timeout', 30)) as response:
                    raw = response.read(16 * 1024 * 1024 + 1)
            except HTTPError as exc:
                if exc.code == 429 or 500 <= exc.code <= 599:
                    retry_after = exc.headers.get('Retry-After', '0')
                    try:
                        delay = float(retry_after)
                    except ValueError:
                        try:
                            delay = (parsedate_to_datetime(retry_after) - datetime.now(timezone.utc)).total_seconds()
                        except (ValueError, TypeError):
                            delay = 0
                    raise Retryable(f"HTTP {exc.code}", max(0, delay)) from exc
                raise
            except (URLError, TimeoutError, socket.timeout) as exc:
                raise Retryable(str(exc)) from exc
        if len(raw) > 16 * 1024 * 1024:
            raise ValueError("Listing exceeds 16 MiB")
        return raw

    def fetch(self, key):
        raw = self.read(key)
        data = json.loads(raw)
        if type(data.get('exists')) is not bool or not isinstance(data.get('entries'), list):
            raise ValueError("Invalid normalized listing")
        next_key = data['next_key']  # Missing next_key is a parse error, not end of pagination.
        if next_key is not None and (not isinstance(next_key, str) or not next_key):
            raise ValueError("Invalid next_key")
        entries = tuple(Entry(**entry) for entry in data['entries'])
        for entry in entries:
            entry.validate()
        return Page(data['exists'], entries, next_key)


class HtmlAdapter(JsonAdapter):
    """Configurable static HTML adapter; selectors must be verified for each source.

    Requires explicit catalog/empty/end selectors to distinguish layout/challenge
    responses from catalog boundaries. Cursor URLs stay on the configured host.
    """
    def fetch(self, key):
        from bs4 import BeautifulSoup
        document = BeautifulSoup(self.read(key), 'html.parser')
        selectors = self.config['selectors']
        if not document.select_one(selectors['catalog']):
            raise ValueError('Catalog root missing: layout change, login or challenge page')
        cards = document.select(selectors['entry'])
        entries = []
        for card in cards:
            link = card.select_one(selectors['link'])
            title = card.select_one(selectors['title'])
            if link is None or title is None or not link.get('href'):
                raise ValueError('Entry link/title missing')
            href = urljoin(self.config['base_url'], link['href'])
            parsed = urlsplit(href)
            if parsed.netloc != urlsplit(self.config['base_url']).netloc:
                raise ValueError('Entry link leaves configured source')
            # Most extensions use relative paths; config can retain absolute URLs if verified.
            url = href if self.config.get('absolute_reader_urls') else parsed.path + ('?' + parsed.query if parsed.query else '')
            thumbnail = card.select_one(selectors['thumbnail']) if selectors.get('thumbnail') else None
            entry = Entry(url, title.get_text(' ', strip=True), thumbnail=urljoin(self.config['base_url'],
                thumbnail.get(self.config.get('thumbnail_attribute', 'src'), '')) if thumbnail else '')
            entry.validate()
            entries.append(entry)
        exists = bool(entries)
        if not exists and not document.select_one(selectors['empty']):
            raise ValueError('No entries and no explicit empty marker')
        next_key = None
        if self.mode == 'sequential':
            next_link = document.select_one(selectors['next'])
            if next_link and next_link.get('href'):
                next_url = urljoin(self.config['base_url'], next_link['href'])
                if urlsplit(next_url).netloc != urlsplit(self.config['base_url']).netloc:
                    raise ValueError('Next link leaves configured source')
                next_key = next_url
            elif not document.select_one(selectors['end']):
                raise ValueError('No next link and no explicit end marker')
        return Page(exists, tuple(entries), next_key)

    def read(self, key):
        if self.mode == 'sequential' and key.startswith('https://'):
            if urlsplit(key).netloc != urlsplit(self.config['base_url']).netloc:
                raise ValueError('Cursor leaves configured source')
            # Keep the shared HTTP error, size, pacing and retry policy.
            config = self.config
            try:
                self.config = {**config, 'url_template': key}
                return super().read(key)
            finally:
                self.config = config
        return super().read(key)


def make_adapter(config):
    adapters = {'json': JsonAdapter, 'html': HtmlAdapter}
    return adapters[config.get('adapter', 'json')](config)
