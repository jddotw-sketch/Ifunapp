import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from catalog.backup import export, validate
from catalog.engine import Fetcher, Store, collect, discover
from catalog.hentaimama import BASE_CONFIG, HentaiMamaAdapter


def listing(page, total=2, next_link=True):
    next_html = f'<a class="dt-pg-next" href="/hentai-series/page/{page + 1}/?filter=alphabet"></a>' if next_link else ''
    return f'''<div class="dt-series-cards"><article class="series-card">
      <a class="sc-poster" href="https://hentaimama.io/tvshows/example-{page}/"><img src="https://hentaimama.io/cover.jpg"></a>
      <h3 class="sc-title"><a>Example {page}</a></h3></article></div>
      <div class="pagination dt-pg" data-page="{page}" data-pages="{total}">{next_html}</div>'''.encode()


class HentaiMamaTests(unittest.TestCase):
    def adapter(self):
        return HentaiMamaAdapter({**BASE_CONFIG, 'snapshot_pages': 2})

    def test_complete_crawl_exports_an_anikku_backup_with_extension_keys(self):
        adapter = self.adapter()
        with tempfile.TemporaryDirectory() as folder, patch.object(adapter, 'read', side_effect=[listing(1), listing(2, next_link=False)]):
            store = Store(str(Path(folder) / 'manifest.sqlite'), adapter)
            try:
                fetch = Fetcher(adapter, interval=0)
                discover(store, adapter, fetch)
                collect(store, fetch)
                result = export(store, adapter, 'anikku', Path(folder) / 'backups')
                self.assertEqual(2, result['entries'])
                self.assertFalse(result['partial'])
                decoded = validate(Path(folder) / 'backups' / result['files'][0]['file'], 'anikku')
                self.assertEqual(8058423919997530503, decoded.entries[0].source)
                self.assertEqual('/tvshows/example-1/', decoded.entries[0].url)
            finally:
                store.close()

    def test_bad_layout_wrong_page_and_boundary_changes_fail(self):
        for key, raw in (('1', b'<html>Challenge</html>'), ('1', listing(2)),
                         ('1', listing(1, total=3)), ('1', listing(1, next_link=False)),
                         ('2', listing(2, next_link=True))):
            with self.subTest(key=key, raw=raw), patch.object(HentaiMamaAdapter, 'read', return_value=raw):
                with self.assertRaises(ValueError): self.adapter().fetch(key)

    def test_foreign_next_link_and_series_keys_fail(self):
        for raw in (listing(1).replace(b'href="/hentai-series/page/2/', b'href="https://foreign.invalid/hentai-series/page/2/'),
                    listing(1).replace(b'https://hentaimama.io/tvshows/', b'https://foreign.invalid/tvshows/')):
            with patch.object(HentaiMamaAdapter, 'read', return_value=raw):
                with self.assertRaises(ValueError): self.adapter().fetch('1')
