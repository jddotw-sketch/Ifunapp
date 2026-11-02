import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

from catalog.adapters import HtmlAdapter, JsonAdapter
from catalog.engine import Retryable


class AdapterTests(unittest.TestCase):
    def config(self):
        return {'name': 'Test', 'media_type': 'video', 'mode': 'monotonic',
                'url_template': 'https://example.invalid/{key}', 'reader_sources': {'anikku': 1}}

    def test_video_cannot_export_to_manga_reader(self):
        config = self.config()
        config['reader_sources'] = {'komikku': 1}
        with self.assertRaises(ValueError):
            JsonAdapter(config)

    def test_collection_identity_does_not_require_a_compatible_reader(self):
        config = self.config()
        complete = JsonAdapter(config)
        config.pop('reader_sources')
        config.pop('media_type')
        config['timeout'] = 90
        collection_only = JsonAdapter(config)
        self.assertEqual({}, collection_only.reader_sources)
        self.assertEqual(complete.identity, collection_only.identity)

    def test_http_failures_are_not_page_boundaries(self):
        for code in (429, 502, 404, 403):
            with patch('catalog.adapters.urlopen', side_effect=HTTPError('url', code, 'error', {'Retry-After': '3'}, None)):
                with self.assertRaises(Retryable if code in (429, 502) else HTTPError):
                    JsonAdapter(self.config()).fetch('1')

    def test_missing_cursor_field_is_parse_error(self):
        with tempfile.TemporaryDirectory() as root:
            config = {**self.config(), 'fixture_dir': root}
            Path(root, '1.json').write_text(json.dumps({'exists': False, 'entries': []}))
            with self.assertRaises(KeyError):
                JsonAdapter(config).fetch('1')

    def test_html_canonical_keys_and_explicit_end(self):
        config = {**self.config(), 'adapter': 'html', 'mode': 'sequential', 'base_url': 'https://example.invalid',
                  'selectors': {'catalog': '#catalog', 'entry': '.card', 'link': 'a', 'title': 'b',
                                'empty': '.empty', 'next': 'a.next', 'end': '.end'}}
        adapter = HtmlAdapter(config)
        with patch.object(adapter, 'read', return_value=b'<div id="catalog"><div class="card"><a href="/item/1"><b>One</b></a></div><i class="end"></i></div>'):
            page = adapter.fetch('1')
            self.assertEqual('/item/1', page.entries[0].url)
            self.assertIsNone(page.next_key)
        with patch.object(adapter, 'read', return_value=b'<h1>Challenge</h1>'):
            with self.assertRaises(ValueError):
                adapter.fetch('1')
        with patch.object(adapter, 'read', return_value=b'<div id="catalog"><i class="empty"></i></div>'):
            with self.assertRaises(ValueError):
                adapter.fetch('1')
