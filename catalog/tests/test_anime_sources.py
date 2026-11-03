from http.client import IncompleteRead
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from bs4 import BeautifulSoup

from catalog.adapters import JsonAdapter
from catalog.anime_sources import AnimeSourceAdapter, SPECS, source_config
from catalog.backup import export, validate
from catalog.engine import Entry, Fetcher, Page, Store


def document(text): return BeautifulSoup(text, 'html.parser')


class AnimeSourcesTests(unittest.TestCase):
    def adapter(self, code): return AnimeSourceAdapter(source_config(code, snapshot_file='unused.json'))

    def test_requested_source_ids_are_fixed_and_supported_listing_is_explicit(self):
        for code in SPECS:
            config=source_config(code,snapshot_file='unused.json')
            self.assertEqual({'anikku':SPECS[code][1]},self.adapter(code).reader_sources)
            config['reader_sources']={'anikku':1}
            with self.assertRaises(ValueError): AnimeSourceAdapter(config)
        with self.assertRaises(ValueError): source_config('movy','latest')

    def test_nyaa_canonical_key_and_explicit_last_page(self):
        raw='''<table class="torrent-list"><tbody><tr><td></td><td>
        <a class="comments" href="/view/123#comments">2</a><a title="Example" href="/view/123">Example</a>
        </td></tr></tbody></table><ul class="pagination"><li class="active">2</li><li class="disabled">Next</li></ul>'''
        for code in ('nyaa','sukebei'):
            page=self.adapter(code).html_page(document(raw),2)
            self.assertEqual('/view/123',page.entries[0].url)
            self.assertIsNone(page.next_key)
            with self.assertRaises(ValueError): self.adapter(code).html_page(document(raw),1)

    def test_torrent_end_and_next_link_host_validation(self):
        for code in ('ptorrent','hentaitorrent'):
            raw=f'''<div class="image-container"><div class="image-wrapper"><a class="overlay" href="{SPECS[code][2]}/example-1.html">Example</a><img src="/cover.jpg"></div></div>
            <div class="pagination"><a class="active">1</a></div>'''
            self.assertIsNone(self.adapter(code).html_page(document(raw),1).next_key)
            bad=raw.replace('</div>','<a href="https://foreign.invalid/page/2">Next</a></div>')
            with self.assertRaises(ValueError): self.adapter(code).html_page(document(bad),1)

    def test_xvideos_includes_zero_page_and_checks_offset_indicator(self):
        raw='''<div id="main"><div id="content"><div class="mozaique cust-nb-cols"><div>
        <div class="thumb"><a href="/video.123/example"><img data-src="https://example.invalid/c.jpg"></a></div>
        <div class="thumb-under"><p class="title">Example</p></div></div></div></div></div>
        <div class="pagination"><a class="active">1</a><a class="no-page next-page" href="/new/1">Next</a></div>'''
        adapter=self.adapter('xvideos')
        self.assertEqual('0',adapter.first_key)
        self.assertEqual('1',adapter.html_page(document(raw),0).next_key)
        with self.assertRaises(ValueError): adapter.html_page(document(raw),1)

    def test_hstream_preserves_series_keys_and_deduplicates_episodes(self):
        raw='''<div class="items-center"><div class="w-full"><a href="/hentai/example-2/"><img alt="Example Episode 2" src="/img/thumb.jpg"></a></div></div>
        <nav aria-label="Pagination Navigation"><span aria-current="page">1</span><a wire:click="nextPage">Next</a></nav>'''
        page=self.adapter('hstream').html_page(document(raw+raw),1)
        self.assertEqual(1,len(page.entries))
        self.assertEqual('/hentai/example/',page.entries[0].url)
        self.assertEqual('Example',page.entries[0].title)
        self.assertEqual('2',page.next_key)

    def test_missav_uses_redirect_prefix_from_canonical_links(self):
        raw='''<div class="thumbnail"><a class="text-secondary" href="https://missav.live/dm539/en/example">Example</a><img data-src="https://example.invalid/a.jpg"></div>
        <span aria-current="page">1</span><span aria-disabled="true">Next</span>'''
        page=self.adapter('missav').html_page(document(raw),1)
        self.assertEqual('/dm539/en/example',page.entries[0].url)
        self.assertIsNone(page.next_key)

    def test_challenge_or_missing_cards_never_means_catalog_end(self):
        for code in ('nyaa','sukebei','ptorrent','hentaitorrent','xvideos','missav','hstream'):
            with self.subTest(code=code),self.assertRaises(ValueError):
                self.adapter(code).html_page(document('<html>Challenge</html>'),1)

    def test_haven_json_keys_entities_and_boundary(self):
        data={'totalPages':2,'data':[{'slug':'example','title':{'rendered':'A &amp; B'},'meta':{'vraven_remote_thumbnail':'cover.jpg'}}]}
        adapter=self.adapter('hentaihaven')
        self.assertEqual('/watch/example/',adapter.json_page(data,1).entries[0].url)
        self.assertEqual('A & B',adapter.json_page(data,1).entries[0].title)
        self.assertIsNone(adapter.json_page(data,2).next_key)
        with self.assertRaises(ValueError): adapter.json_page({'data':[]},1)

    def test_movy_seasons_are_encoded_with_actual_anikku_enum_ordinal(self):
        data={'page':1,'total_pages':1,'results':[{'id':123,'media_type':'tv','name':'Example'}]}
        adapter=self.adapter('movy');page=adapter.json_page(data,1)
        self.assertEqual(0,page.entries[0].fetch_type)  # Real reader: Seasons=0, Episodes=1.
        with tempfile.TemporaryDirectory() as folder:
            store=Store(Path(folder)/'manifest.sqlite',adapter)
            try:
                store.add_page('1',1);store.commit_page('1',page)
                with store.db:store.put_checkpoint('discovered','yes')
                result=export(store,adapter,'anikku',folder)
                self.assertIn('Movy-Anikku-Full-',result['files'][0]['file'])
                decoded=validate(Path(folder)/result['files'][0]['file'],'anikku')
                self.assertTrue(decoded.entries[0].HasField('fetch_type'))
                self.assertEqual(0,decoded.entries[0].fetch_type)
            finally:store.close()
        with self.assertRaises(ValueError): adapter.json_page(data,2)

    def test_hanime_persists_full_snapshot_and_uses_extension_series_grouping(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'snapshot.json';config=source_config('hanime',snapshot_file=path)
            hits=[{'name':'Example 2','slug':'example-2','likes':3},{'name':'Example 1','slug':'example-1','likes':2},
                  {'name':'Example Season 2','slug':'example-season-2','likes':1}]
            with patch.object(AnimeSourceAdapter,'read',return_value=json.dumps({'data':hits}).encode()):
                page=AnimeSourceAdapter(config).fetch('1')
            self.assertEqual(2,len(page.entries));self.assertIsNone(page.next_key)
            self.assertEqual('/videos/hentai/example-2',page.entries[0].url)
            with patch.object(AnimeSourceAdapter,'read',side_effect=AssertionError('must reuse disk snapshot')):
                self.assertEqual(page,AnimeSourceAdapter(config).fetch('1'))

    def test_interrupted_http_body_is_retried_instead_of_stopping_collection(self):
        config={'name':'Example','mode':'sequential','url_template':'https://example.invalid/{key}'}
        class Response:
            def __init__(self,broken):self.broken=broken
            def __enter__(self):return self
            def __exit__(self,*_):pass
            def read(self,*_):
                if self.broken:raise IncompleteRead(b'cut short')
                return b'{"exists":true,"entries":[{"url":"/example","title":"Example"}],"next_key":null}'
        with patch('catalog.adapters.urlopen',side_effect=[Response(True),Response(False)]):
            page=Fetcher(JsonAdapter(config),interval=0,retries=1,sleep=lambda _:None)('1')
            self.assertEqual('/example',page.entries[0].url)
