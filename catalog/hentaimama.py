"""HentaiMama listing adapter, matching the published English extension's keys.

Reference: yuzono/anime-extensions HentaiMama.kt and anime-repo index v16.9.
Only listing metadata is collected. No episode or video requests are made.
"""
from urllib.parse import urljoin, urlsplit

from bs4 import BeautifulSoup

from .adapters import JsonAdapter
from .engine import Entry, Page

BASE_CONFIG = {
    'name': 'HentaiMama', 'mode': 'sequential', 'first_key': '1',
    'media_type': 'video', 'reader_sources': {'anikku': 8058423919997530503},
    'base_url': 'https://hentaimama.io',
    'url_template': 'https://hentaimama.io/hentai-series/page/{key}/?filter=alphabet',
    'sort': 'alphabet',
}


class HentaiMamaAdapter(JsonAdapter):
    @staticmethod
    def pagination(document, requested):
        bars = document.select('div.pagination.dt-pg')
        if not document.select_one('div.dt-series-cards') or not bars:
            raise ValueError('HentaiMama catalog or pagination missing; not an end signal')
        states = {(bar.get('data-page'), bar.get('data-pages')) for bar in bars}
        if len(states) != 1:
            raise ValueError('HentaiMama pagination indicators disagree')
        current, total = next(iter(states))
        if not current or not total or not current.isdigit() or not total.isdigit():
            raise ValueError('HentaiMama pagination is invalid')
        if int(current) != requested or not requested <= int(total) <= 100_000:
            raise ValueError('HentaiMama returned the wrong page or invalid boundary')
        return int(total), bars

    def read_page(self, key):
        if not str(key).isdigit() or int(key) < 1:
            raise ValueError('Invalid HentaiMama page')
        first = self.config['base_url'] + '/hentai-series/?filter=alphabet'
        return BeautifulSoup(self.read(str(key), url_override=first if int(key) == 1 else None), 'html.parser')

    def fetch(self, key):
        number = int(key) if str(key).isdigit() else 0
        document = self.read_page(key)
        total, bars = self.pagination(document, number)
        if total != self.config['snapshot_pages']:
            raise ValueError('HentaiMama page count changed during collection; review this snapshot')
        expected = f'/hentai-series/page/{number + 1}/?filter=alphabet'
        for bar in bars:
            links = bar.select('a.dt-pg-next[href]')
            if number < total:
                if len(links) != 1:
                    raise ValueError('HentaiMama next-page link is missing')
                link = urlsplit(urljoin(self.config['base_url'], links[0]['href']))
                if link.scheme != 'https' or link.netloc != 'hentaimama.io' or link.path + '?' + link.query != expected:
                    raise ValueError('HentaiMama next-page link does not match pagination')
            elif links:
                raise ValueError('HentaiMama final page unexpectedly has a next link')
        entries = []
        for card in document.select('div.dt-series-cards article.series-card'):
            link, title = card.select_one('a.sc-poster[href]'), card.select_one('h3.sc-title a')
            if link is None or title is None:
                raise ValueError('HentaiMama card layout changed')
            url = urlsplit(urljoin(self.config['base_url'], link['href']))
            if url.scheme != 'https' or url.netloc != 'hentaimama.io' or not url.path.startswith('/tvshows/') or url.query or url.fragment:
                raise ValueError('HentaiMama returned a noncanonical series key')
            image = link.select_one('img[src]')
            cover = urljoin(self.config['base_url'], image['src']) if image else ''
            if cover and not cover.startswith('https://'):
                cover = ''
            entry = Entry(url=url.path, title=title.get_text(' ', strip=True), thumbnail=cover)
            entry.validate()
            entries.append(entry)
        if not entries:
            raise ValueError('HentaiMama listing is empty; not an end signal')
        return Page(True, tuple(entries), str(number + 1) if number < total else None)


def prepare_hentaimama():
    adapter = HentaiMamaAdapter(BASE_CONFIG)
    total, _ = adapter.pagination(adapter.read_page('1'), 1)
    return {**BASE_CONFIG, 'snapshot_pages': total}
