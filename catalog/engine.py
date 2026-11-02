from __future__ import annotations

import json
import sqlite3
import time
import threading
from collections import deque
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class Entry:
    url: str
    title: str
    author: str = ""
    artist: str = ""
    description: str = ""
    thumbnail: str = ""
    genres: tuple[str, ...] = ()
    status: int = 0

    def validate(self):
        if not self.url.strip() or not self.title.strip():
            raise ValueError("Adapter returned an empty reader URL or title")
        if not 0 <= self.status <= 6:
            raise ValueError("Invalid reader status")


@dataclass(frozen=True)
class Page:
    exists: bool
    entries: tuple[Entry, ...] = ()
    next_key: str | None = None


class Adapter(Protocol):
    # Identity includes all parameters affecting catalog membership/order.
    identity: str
    mode: str  # monotonic or sequential (including numbered sources with gaps)
    first_key: str
    reader_sources: dict[str, int]
    name: str

    def fetch(self, key: str) -> Page: ...


class Store:
    def __init__(self, path: str | Path, adapter: Adapter):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=FULL;
            CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS pages(
                key TEXT PRIMARY KEY, ordinal INTEGER NOT NULL UNIQUE,
                status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
                error TEXT, payload TEXT, next_key TEXT);
            CREATE TABLE IF NOT EXISTS entries(
                id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, payload TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS checkpoints(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS probes(key TEXT PRIMARY KEY, payload TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS export_entries(
                reader TEXT NOT NULL, entry_id INTEGER NOT NULL, ordinal INTEGER NOT NULL,
                PRIMARY KEY(reader, entry_id), UNIQUE(reader, ordinal));
        """)
        identity = json.dumps([adapter.identity, adapter.mode, adapter.first_key], sort_keys=True)
        old = self.get("identity")
        if old is not None and old != identity:
            self.db.close()
            raise ValueError("Manifest belongs to another adapter/configuration")
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO settings VALUES('identity',?)", (identity,))
            # Single-process ownership is enforced by the CLI lock. A previous process died.
            self.db.execute("UPDATE pages SET status='pending' WHERE status='processing'")

    def close(self):
        self.db.close()

    def get(self, key):
        row = self.db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def checkpoint(self, key):
        row = self.db.execute("SELECT value FROM checkpoints WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def put_checkpoint(self, key, value):
        self.db.execute("INSERT OR REPLACE INTO checkpoints VALUES (?,?)", (key, str(value)))

    def add_page(self, key, ordinal):
        self.db.execute("INSERT OR IGNORE INTO pages(key,ordinal) VALUES (?,?)", (key, ordinal))

    def commit_page(self, key, page):
        # Metadata, status and cursor checkpoint are one crash-safe commit.
        for entry in page.entries:
            entry.validate()
        with self.db:
            for entry in page.entries:
                self.db.execute("INSERT OR IGNORE INTO entries(url,payload) VALUES (?,?)",
                                (entry.url, json.dumps(asdict(entry), ensure_ascii=False)))
            self.db.execute("UPDATE pages SET status='done',error=NULL,next_key=? WHERE key=?",
                            (page.next_key, key))

    def stats(self):
        return {"pages": dict(self.db.execute("SELECT status,count(*) FROM pages GROUP BY status")),
                "entries": self.db.execute("SELECT count(*) FROM entries").fetchone()[0],
                "discovery_complete": self.checkpoint("discovered") == "yes"}


class Fetcher:
    """All discovery and collection requests use the same pacing/retry policy."""
    def __init__(self, adapter, interval=1.0, retries=5, sleep=time.sleep, clock=time.monotonic):
        if interval < 0 or retries < 0:
            raise ValueError("Negative interval/retry count")
        self.adapter, self.interval, self.retries = adapter, interval, retries
        self.sleep, self.clock, self.last = sleep, clock, float('-inf')
        self.lock, self.cooldown = threading.Lock(), float('-inf')

    def __call__(self, key):
        for attempt in range(self.retries + 1):
            with self.lock:
                self.sleep(max(0, max(self.last + self.interval, self.cooldown) - self.clock()))
                self.last = self.clock()
            try:
                page = self.adapter.fetch(key)
                if not isinstance(page, Page) or (not page.exists and page.entries):
                    raise ValueError("Invalid adapter page")
                return page
            except Retryable as exc:
                if attempt == self.retries:
                    raise
                with self.lock:
                    self.cooldown = max(self.cooldown,
                        self.clock() + max(exc.retry_after, min(300, 2 ** attempt)))


class Retryable(Exception):
    def __init__(self, message, retry_after=0):
        super().__init__(message)
        self.retry_after = retry_after


def _page_json(page):
    return json.dumps(asdict(page), ensure_ascii=False)


def _page_decode(payload):
    data = json.loads(payload)
    return Page(data["exists"], tuple(Entry(**e) for e in data["entries"]), data["next_key"])


def discover(store, adapter, fetch, max_pages=1_000_000):
    if max_pages < 1:
        raise ValueError("max_pages must be positive")
    if store.checkpoint("discovered") == "yes":
        return
    if adapter.mode == "monotonic":
        if adapter.first_key != "1":
            raise ValueError("Monotonic adapters must use 1-based page keys")

        def probe(n):
            key = str(n)
            row = store.db.execute("SELECT payload FROM probes WHERE key=?", (key,)).fetchone()
            if row:
                return _page_decode(row[0])
            page = fetch(key)  # Errors are never interpreted as the end of a catalog.
            with store.db:
                store.db.execute("INSERT INTO probes VALUES (?,?)", (key, _page_json(page)))
            return page

        low, high = 0, 1
        while probe(high).exists:
            low = high
            if high > max_pages:
                raise ValueError("Discovery safety limit reached; increase max_pages")
            high = min(high * 2, max_pages + 1)
        while high - low > 1:
            mid = (low + high) // 2
            if probe(mid).exists:
                low = mid
            else:
                high = mid
        # Verify nearby pages. This is a sanity check, not proof of monotonicity.
        for n in range(max(1, low - 1), min(max_pages + 1, high + 1) + 1):
            if probe(n).exists != (n <= low):
                raise ValueError("Inconsistent boundary; use a sequential adapter")
        with store.db:
            for n in range(1, low + 1):
                store.add_page(str(n), n)
            store.put_checkpoint("discovered", "yes")
    elif adapter.mode == "sequential":
        key = store.checkpoint("cursor") or adapter.first_key
        ordinal = int(store.checkpoint("cursor_ordinal") or 1)
        while True:
            if ordinal > max_pages:
                raise ValueError("Discovery safety limit reached")
            seen = store.db.execute("SELECT 1 FROM pages WHERE key=?", (key,)).fetchone()
            if seen:
                raise ValueError("Cursor cycle detected")
            page = fetch(key)
            if page.next_key == key:
                raise ValueError("Cursor did not advance")
            with store.db:
                # Persist fetched listings so collection doesn't fetch cursors twice.
                store.db.execute("INSERT INTO pages(key,ordinal,payload,next_key) VALUES (?,?,?,?)",
                                 (key, ordinal, _page_json(page), page.next_key))
                if page.next_key is None:
                    store.put_checkpoint("discovered", "yes")
                else:
                    store.put_checkpoint("cursor", page.next_key)
                    store.put_checkpoint("cursor_ordinal", ordinal + 1)
            if page.next_key is None:
                break
            key, ordinal = page.next_key, ordinal + 1
    else:
        raise ValueError("Unknown adapter discovery mode")


def collect(store, fetch, workers=1):
    if not 1 <= workers <= 32:
        raise ValueError('workers must be between 1 and 32')
    # Fetch in parallel, commit in page order. Only the owner thread accesses SQLite.
    # At most workers listings are buffered, so slow commits cannot grow memory forever.
    keys = store.db.execute("SELECT key FROM pages WHERE status!='done' ORDER BY ordinal").fetchall()
    queue = deque()
    iterator = iter(keys)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        def schedule():
            item = next(iterator, None)
            if item is None:
                return False
            key = item[0]
            with store.db:
                store.db.execute("UPDATE pages SET status='processing',attempts=attempts+1 WHERE key=?", (key,))
            row = store.db.execute("SELECT payload FROM pages WHERE key=?", (key,)).fetchone()
            probe = store.db.execute("SELECT payload FROM probes WHERE key=?", (key,)).fetchone()
            if row[0] or probe:
                future = Future()
                try:
                    future.set_result(_page_decode(row[0] or probe[0]))
                except Exception as exc:
                    future.set_exception(exc)
            else:
                future = pool.submit(fetch, key)
            queue.append((key, future, bool(row[0])))
            return True

        try:
            for _ in range(workers):
                schedule()
            while queue:
                key, future, cached_cursor = queue.popleft()
                try:
                    page = future.result()
                    if not page.exists and store.checkpoint('discovered') == 'yes' and not cached_cursor:
                        raise ValueError('Previously discovered numbered page disappeared; retry or rediscover')
                    store.commit_page(key, page)
                except Exception as exc:
                    with store.db:
                        store.db.execute("UPDATE pages SET status='failed',error=? WHERE key=?", (str(exc), key))
                    raise
                schedule()
        finally:
            for _, future, _ in queue:
                future.cancel()
            with store.db:
                store.db.execute("UPDATE pages SET status='pending' WHERE status='processing'")


class ManifestLock:
    """OS advisory lock: released after crashes, unlike persistent lock-file flags."""
    def __init__(self, path):
        self.path = str(Path(path).resolve()) + ".lock"

    def __enter__(self):
        self.file = open(self.path, "a+b")
        self.file.seek(0)
        self.file.write(b"0")
        self.file.flush()
        self.file.seek(0)
        try:
            if __import__('os').name == 'nt':
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise RuntimeError("Manifest is already in use") from None
        return self

    def __exit__(self, *args):
        self.file.close()
