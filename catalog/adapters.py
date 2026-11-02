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
        self.identity = hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()

    def fetch(self, key):
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
