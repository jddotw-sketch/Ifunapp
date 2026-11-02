import json
import tempfile
import unittest
import time
from pathlib import Path

from catalog.backup import export, validate
from catalog.engine import Entry, Fetcher, Page, Retryable, Store, collect, discover


class Fake:
    name = 'Fixture'
    mode = 'monotonic'
    first_key = '1'
    identity = 'fixture-v1'
    reader_sources = {'anikku': -123456789012345, 'komikku': -123456789012345}

    def __init__(self, count=30_000):
        self.count, self.calls, self.fail = count, [], None

    def fetch(self, key):
        self.calls.append(key)
        if key == self.fail:
            raise Retryable('temporary failure')
        n = int(key)
        return Page(n <= self.count, (Entry(f'/entry/{n}', f'Title {n}'),) if n <= self.count else ())


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.adapter = Fake()
        self.store = Store(self.root / 'manifest.sqlite', self.adapter)
        self.addCleanup(lambda: self.store.close())

    def test_30000_page_boundary_uses_logarithmic_probes(self):
        discover(self.store, self.adapter, self.adapter.fetch)
        self.assertEqual(30_000, self.store.db.execute('SELECT count(*) FROM pages').fetchone()[0])
        self.assertLess(len(self.adapter.calls), 40)

    def test_transient_error_never_becomes_end_and_probes_resume(self):
        self.adapter.fail = '8'
        with self.assertRaises(Retryable):
            discover(self.store, self.adapter, self.adapter.fetch)
        self.assertIsNone(self.store.checkpoint('discovered'))
        self.adapter.fail = None
        discover(self.store, self.adapter, self.adapter.fetch)
        self.assertEqual(1, self.adapter.calls.count('4'))
        self.assertEqual(2, self.adapter.calls.count('8'))

    def test_collection_restart_dedup_and_failure_checkpoint(self):
        self.adapter.count = 10
        discover(self.store, self.adapter, self.adapter.fetch)
        self.adapter.fail = '3'  # Not an exponential probe or boundary neighbor.
        with self.assertRaises(Retryable):
            collect(self.store, self.adapter.fetch)
        self.assertEqual(2, self.store.stats()['entries'])
        self.store.close()
        self.store = Store(self.root / 'manifest.sqlite', self.adapter)
        self.adapter.fail = None
        collect(self.store, self.adapter.fetch)
        collect(self.store, self.adapter.fetch)
        self.assertEqual(10, self.store.stats()['entries'])
        self.store.commit_page('1', Page(True, (Entry('/entry/1', 'Duplicate'),)))
        self.assertEqual(10, self.store.stats()['entries'])

    def test_cursor_gaps_and_resume(self):
        self.store.close()
        self.adapter.mode = 'sequential'
        self.store = Store(self.root / 'cursor.sqlite', self.adapter)
        pages = {'1': Page(True, (Entry('/a', 'A'),), 'gap'),
                 'gap': Page(False, (), 'tail'), 'tail': Page(True, (Entry('/a', 'A'), Entry('/b', 'B')))}
        calls = []

        def fetch(key):
            calls.append(key)
            if key == 'tail' and calls.count('tail') == 1:
                raise Retryable('restart')
            return pages[key]

        with self.assertRaises(Retryable):
            discover(self.store, self.adapter, fetch)
        discover(self.store, self.adapter, fetch)
        collect(self.store, lambda key: self.fail('Cached cursors must not be refetched'))
        self.assertEqual(['1', 'gap', 'tail', 'tail'], calls)
        self.assertEqual(2, self.store.stats()['entries'])

    def test_cursor_cycle_is_error(self):
        self.store.close()
        self.adapter.mode = 'sequential'
        self.store = Store(self.root / 'cycle.sqlite', self.adapter)
        with self.assertRaises(ValueError):
            discover(self.store, self.adapter, lambda key: Page(True, (), '2' if key == '1' else '1'))
        self.assertIsNone(self.store.checkpoint('discovered'))

    def test_backoff_and_rate_limit(self):
        clock = [0.0]
        times, sleeps = [], []

        def sleep(delay):
            sleeps.append(delay)
            clock[0] += delay

        def fetch(key):
            times.append(clock[0])
            if len(times) == 1:
                raise Retryable('429', retry_after=7)
            return Page(True)

        self.adapter.fetch = fetch
        requester = Fetcher(self.adapter, interval=2, sleep=sleep, clock=lambda: clock[0])
        requester('1')
        requester('2')
        self.assertEqual([0, 7, 9], times)

    def seed(self, count):
        with self.store.db:
            self.store.db.executemany('INSERT INTO entries(url,payload) VALUES (?,?)', (
                (f'/entry/{i}', json.dumps({'url': f'/entry/{i}', 'title': f'Title {i} 🐈',
                 'author': '', 'artist': '', 'description': '', 'thumbnail': '', 'genres': ['Test'], 'status': 0}))
                for i in range(count)))
            self.store.put_checkpoint('discovered', 'yes')

    def test_encoders_and_category_and_file_boundaries(self):
        self.seed(50_001)
        for reader in ('anikku', 'komikku'):
            result = export(self.store, self.adapter, reader, self.root / reader)
            self.assertEqual([50_000, 1], [f['entries'] for f in result['files']])
            first = validate(self.root / reader / result['files'][0]['file'], reader)
            last = validate(self.root / reader / result['files'][1]['file'], reader)
            self.assertEqual(1, first.entries[4999].categories[0])
            self.assertEqual(2, first.entries[5000].categories[0])
            self.assertEqual(11, last.entries[0].categories[0])
            self.assertEqual('Title 0 🐈', first.entries[0].title)
            self.assertEqual(-123456789012345, first.entries[0].source)
            hashes = [f['sha256'] for f in result['files']]
            self.assertEqual(hashes, [f['sha256'] for f in export(self.store, self.adapter, reader, self.root / reader)['files']])
            with self.assertRaises(ValueError):
                validate(self.root / reader / result['files'][0]['file'], 'komikku' if reader == 'anikku' else 'anikku')

    def test_partial_export_and_stable_categories(self):
        self.seed(5_001)
        with self.store.db:
            self.store.add_page('1', 1)
        with self.assertRaises(ValueError):
            export(self.store, self.adapter, 'anikku', self.root)
        result = export(self.store, self.adapter, 'anikku', self.root, allow_partial=True)
        before = validate(self.root / result['files'][0]['file'], 'anikku')
        self.store.commit_page('1', Page(True, (Entry('/new', 'New'),)))
        after_result = export(self.store, self.adapter, 'anikku', self.root)
        after = validate(self.root / after_result['files'][0]['file'], 'anikku')
        self.assertEqual(before.entries[5000].SerializeToString(), after.entries[5000].SerializeToString())

    def test_incompatible_reader_rejected(self):
        self.adapter.reader_sources = {'anikku': 1}
        with self.assertRaises(ValueError):
            export(self.store, self.adapter, 'komikku', self.root)

    def test_manifest_identity_guard(self):
        self.adapter.identity = 'different'
        with self.assertRaises(ValueError):
            Store(self.root / 'manifest.sqlite', self.adapter)

    def test_export_source_mapping_cannot_silently_change(self):
        self.seed(1)
        self.adapter.reader_sources = dict(self.adapter.reader_sources)
        export(self.store, self.adapter, 'anikku', self.root)
        self.adapter.reader_sources['anikku'] = 987
        with self.assertRaises(ValueError):
            export(self.store, self.adapter, 'anikku', self.root)

    def test_atomic_page_validation(self):
        with self.store.db:
            self.store.add_page('1', 1)
        with self.assertRaises(ValueError):
            self.store.commit_page('1', Page(True, (Entry('/good', 'Good'), Entry('', 'Bad'))))
        self.assertEqual(0, self.store.stats()['entries'])

    def test_parallel_fetch_keeps_commit_order_and_resumes(self):
        with self.store.db:
            for n in range(1, 9):
                self.store.add_page(str(n), n)
            self.store.put_checkpoint('discovered', 'yes')

        def fetch(key):
            if key == '1':
                time.sleep(0.02)  # Later pages finish first.
            if key == '5':
                raise Retryable('failure')
            return Page(True, (Entry('/same', f'Page {key}'), Entry('/' + key, key)))

        with self.assertRaises(Retryable):
            collect(self.store, fetch, workers=4)
        self.assertEqual(5, self.store.stats()['entries'])
        self.assertNotIn('processing', self.store.stats()['pages'])
        collect(self.store, lambda key: Page(True, (Entry('/' + key, key),)), workers=4)
        self.assertEqual(9, self.store.stats()['entries'])
        row = self.store.db.execute("SELECT payload FROM entries WHERE url='/same'").fetchone()
        self.assertEqual('Page 1', json.loads(row[0])['title'])


if __name__ == '__main__':
    unittest.main()
