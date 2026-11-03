"""Listing-only ports of the user's Yuzono extensions.

Reference commit 82f89fde63e90090b4868b2003a812c19f714a39 in
yuzono/anime-extensions. No playback, torrent or media requests are made.
Full means the exposed listing; upstream browse/search limits still apply.
"""
import html
import json
from pathlib import Path
import re
from urllib.parse import parse_qs, urljoin, urlsplit

from bs4 import BeautifulSoup

from .adapters import JsonAdapter
from .engine import Entry, Page

SPECS = {
    'nyaa': ('Nyaa (Torrent)', 5310001068353260432, 'https://nyaa.si'),
    'sukebei': ('Nyaa Sukebein (Torrent)', 9136480360798112194, 'https://sukebei.nyaa.si'),
    'ptorrent': ('PTorrent (Torrent)', 2514899426758091629, 'https://www.ptorrents.com'),
    'hentaitorrent': ('Hentai Torrent (Torrent)', 5093529078954574163, 'https://www.hentaitorrents.com'),
    'xvideos': ('Xvideos', 8016727706963226859, 'https://www.xvideos.com'),
    'missav': ('MissAV', 168969881246615628, 'https://missav.live'),
    'hstream': ('Hstream', 8733115911403916257, 'https://hstream.moe'),
    'hentaihaven': ('HentaiHaven', 1700779054663752842, 'https://hentaihaven.xxx'),
    'movy': ('Movy', 8881276529694565186, 'https://db.wecollege.net'),
    'hanime': ('hanime.tv', 2649711761599612719, 'https://guest.freeanimehentai.net'),
}


def source_config(code, listing='popular', snapshot_file=None):
    name, source_id, base = SPECS[code]
    if listing not in ('popular', 'latest'):
        raise ValueError('Unsupported listing')
    if code in ('movy', 'ptorrent', 'hentaitorrent', 'xvideos') and listing != 'popular':
        raise ValueError('This adapter exposes the extension popular listing only')
    paths = {
        'nyaa': '/?f=0&c=1_0&p={key}' + ('&s=seeders&o=desc' if listing == 'popular' else ''),
        'sukebei': '/?f=0&c=1_1&p={key}' + ('&s=seeders&o=desc' if listing == 'popular' else ''),
        'ptorrent': '/page/{key}', 'hentaitorrent': '/page/{key}', 'xvideos': '/new/{key}',
        'missav': '/en/' + ('today-hot' if listing == 'popular' else 'new') + '?page={key}',
        'hstream': '/search?order=' + ('view-count' if listing == 'popular' else 'recently-uploaded') + '&page={key}',
        'hentaihaven': '/api/manga/?per_page=24&live=1&locale=en&orderby=' + ('title&order=asc' if listing == 'popular' else 'date&order=desc') + '&page={key}',
        'movy': '/3/trending/all/week?language=en-US&page={key}',
        'hanime': '/api/v11/search_hvs',
    }
    config = {'source_code': code, 'name': name, 'base_url': base, 'url_template': base + paths[code],
              'mode': 'sequential', 'first_key': '0' if code == 'xvideos' else '1',
              'media_type': 'video', 'reader_sources': {'anikku': source_id},
              'listing': listing, 'user_agent': 'Mozilla/5.0 Ifunapp-Catalog/1.0', 'timeout': 45}
    if code == 'hanime':
        if snapshot_file is None:
            raise ValueError('hanime.tv needs a durable API snapshot path')
        config['snapshot_file'] = str(snapshot_file)
    return config


class AnimeSourceAdapter(JsonAdapter):
    def __init__(self, config):
        super().__init__(config)
        self.code = config['source_code']
        if config['reader_sources'] != {'anikku': SPECS[self.code][1]}:
            raise ValueError('Source ID does not match the referenced extension')
        self.hits = None

    def key_url(self, href):
        parsed = urlsplit(urljoin(self.config['base_url'], href))
        base = urlsplit(self.config['base_url'])
        if parsed.scheme != 'https' or parsed.netloc != base.netloc or parsed.fragment:
            raise ValueError('Source returned a foreign or invalid entry URL')
        return parsed.path + ('?' + parsed.query if parsed.query else '')

    @staticmethod
    def current_number(elements, expected):
        if not elements:
            raise ValueError('Active page indicator missing; not an end signal')
        for element in elements:
            match = re.match(r'\s*(\d+)', element.get_text(' ', strip=True))
            if not match or int(match[1]) != expected:
                raise ValueError('Source returned the wrong page')

    def next_number(self, links, number, parameter='page'):
        if not links:
            return None
        for link in links:
            href = link.get('href')
            if not href:
                raise ValueError('Next link has no URL')
            parsed = urlsplit(urljoin(self.config['base_url'], href))
            if parsed.scheme != 'https' or parsed.netloc != urlsplit(self.config['base_url']).netloc:
                raise ValueError('Next link leaves source')
            if parameter:
                value = parse_qs(parsed.query).get(parameter, [''])[0]
            else:
                value = parsed.path.rstrip('/').split('/')[-1]
            if value != str(number + 1):
                raise ValueError('Next-page URL does not match the current page')
        return str(number + 1)

    def fetch(self, key):
        if not str(key).isdigit() or int(key) < int(self.first_key):
            raise ValueError('Invalid source page key')
        number = int(key)
        if self.code == 'hanime':
            return self.hanime_page(number)
        raw = self.read(key, url_override=self.config['base_url'] + '/' if self.code == 'xvideos' and number == 0 else None)
        if self.code in ('hentaihaven', 'movy'):
            return self.json_page(json.loads(raw), number)
        return self.html_page(BeautifulSoup(raw, 'html.parser'), number)

    def json_page(self, data, number):
        haven = self.code == 'hentaihaven'
        total, items = data.get('totalPages' if haven else 'total_pages'), data.get('data' if haven else 'results')
        if type(total) is not int or not 1 <= number <= total or not isinstance(items, list) or not items:
            raise ValueError('API listing or pagination missing/empty')
        if not haven and data.get('page') != number:
            raise ValueError('API returned the wrong page')
        entries = []
        for item in items:
            if haven:
                slug = item.get('slug', '')
                if not re.fullmatch(r'[A-Za-z0-9_-]+', slug):
                    raise ValueError('Invalid HentaiHaven canonical slug')
                title = html.unescape(item['title']['rendered'])
                image = (item.get('meta') or {}).get('vraven_remote_thumbnail') or ''
                if image and not image.startswith('https://'):
                    image = urljoin('https://img.hentaihaven.xxx/', image)
                entry = Entry('/watch/' + slug + '/', title, thumbnail=image)
            else:
                media_type = item.get('media_type') or ('movie' if item.get('title') is not None else 'tv')
                if media_type == 'person':
                    continue  # Trending/all can contain people; these have no playable reader key.
                if media_type not in ('movie', 'tv') or type(item.get('id')) is not int:
                    raise ValueError('Invalid Movy media identity')
                entry = Entry(f'/{media_type}/{item["id"]}', item.get('title') or item.get('name') or '',
                    thumbnail='https://image.tmdb.org/t/p/w500' + item['poster_path'] if item.get('poster_path') else '',
                    fetch_type=0 if media_type == 'tv' else 1)
            entry.validate()
            entries.append(entry)
        return Page(True, tuple(entries), str(number + 1) if number < total else None)

    def html_page(self, document, number):
        if self.code in ('nyaa', 'sukebei'):
            cards = document.select('table.torrent-list tbody tr')
            self.current_number(document.select('ul.pagination li.active'), number)
            links = document.select('ul.pagination a[rel=next]')
            next_key = self.next_number(links, number, 'p')
            if next_key is None and not document.select_one('ul.pagination li:last-child.disabled'):
                raise ValueError('Nyaa terminal pagination is not explicit')
        elif self.code in ('ptorrent', 'hentaitorrent'):
            cards = document.select('div.image-container div.image-wrapper')
            self.current_number(document.select('div.pagination a.active'), number)
            links = [a for a in document.select('div.pagination a') if a.get_text(strip=True) == 'Next']
            next_key = self.next_number(links, number, None)
            if next_key is None:
                numbers = [int(a.get_text(strip=True)) for a in document.select('div.pagination a') if a.get_text(strip=True).isdigit()]
                if not numbers or max(numbers) != number:
                    raise ValueError('Torrent listing end is not explicit')
        elif self.code == 'xvideos':
            cards = [card for card in document.select('div#main div#content div.mozaique.cust-nb-cols > div') if card.select_one('div.thumb a[href]')]
            if number != 0:
                self.current_number(document.select('div.pagination a.active'), number + 1)
            # The homepage labels its active page "Newest" rather than "1".
            # Its explicit /new/1 link establishes the zero-based first page.
            next_key = self.next_number(document.select('a.no-page.next-page'), number, None)
            if number == 0 and next_key != '1':
                raise ValueError('Xvideos homepage did not publish its first next-page link')
            if next_key is None and document.select_one('div.pagination a.last-page'):
                raise ValueError('Xvideos lost its next link before the last page')
        elif self.code in ('missav', 'hstream'):
            self.current_number(document.select('span[aria-current=page]'), number)
            if self.code == 'missav':
                cards = document.select('div.thumbnail')
                next_key = self.next_number(document.select('a[rel=next]'), number)
                if next_key is None and not document.select_one('span[aria-disabled=true]'):
                    raise ValueError('MissAV terminal navigation is not explicit')
            else:
                cards = document.select('div.items-center div.w-full > a:has(img)')
                controls = document.select('nav[aria-label="Pagination Navigation"]')
                if not controls:
                    raise ValueError('Hstream navigation missing')
                next_key = str(number + 1) if any(a.get('wire:click') == 'nextPage' for nav in controls for a in nav.select('a')) else None
                if next_key is None and not any('Next' in x.get('aria-label','') for nav in controls for x in nav.select('[aria-disabled=true]')):
                    raise ValueError('Hstream terminal navigation is not explicit')
        else:
            raise ValueError('Unsupported HTML source')
        if not cards:
            raise ValueError('Listing cards missing/empty; not an end signal')
        entries = {}
        for card in cards:
            cover = ''
            if self.code in ('nyaa', 'sukebei'):
                link = card.select_one('td:nth-child(2) a:not(.comments)[title]')
                if link is None: raise ValueError('Nyaa torrent card changed')
                key, title = self.key_url(link['href']), link['title']
                if not re.fullmatch(r'/view/\d+', key): raise ValueError('Invalid Nyaa reader key')
            elif self.code in ('ptorrent', 'hentaitorrent'):
                link, image = card.select_one('a.overlay[href]'), card.select_one('img')
                if link is None: raise ValueError('Torrent card changed')
                key, title = self.key_url(link['href']), link.get_text(' ', strip=True)
                if not key.endswith('.html'): raise ValueError('Invalid torrent reader key')
                cover = image.get('src', '') if image else ''
            elif self.code == 'xvideos':
                link, title_element = card.select_one('div.thumb a[href]'), card.select_one('div.thumb-under p.title')
                if link is None or title_element is None: raise ValueError('Xvideos card changed')
                key, title = self.key_url(link['href']), title_element.get_text(' ', strip=True)
                if not key.startswith('/video'): raise ValueError('Invalid Xvideos reader key')
                image = link.select_one('img');cover = image.get('data-src','') if image else ''
            elif self.code == 'missav':
                link, image = card.select_one('a.text-secondary[href]'), card.select_one('img')
                if link is None: raise ValueError('MissAV card changed')
                key, title = self.key_url(link['href']), link.get_text(' ', strip=True)
                cover = image.get('data-src','') if image else ''
            else:
                image = card.select_one('img[alt]')
                if image is None: raise ValueError('Hstream card changed')
                original = self.key_url(card['href']).rstrip('/')
                match = re.fullmatch(r'(.+)-(\d+(?:\.\d+)?)', original)
                if not match: raise ValueError('Hstream episode key changed')
                key = match[1] + '/'
                title = re.sub(r'\s*(?:[-–—]\s*|\bEpisode\s*)\d+(?:\.\d+)?\s*$', '', image['alt'], flags=re.I).strip()
                image_url = urljoin(self.config['base_url'], image.get('src', ''))
                cover = image_url.rsplit('/',1)[0] + '/cover-ep-' + match[2] + '.webp'
            if cover: cover = urljoin(self.config['base_url'], cover)
            if cover and not cover.startswith('https://'): cover = ''
            entry = Entry(key, title, thumbnail=cover)
            entry.validate()
            entries.setdefault(key, entry)
        return Page(True, tuple(entries.values()), next_key)

    @staticmethod
    def series_title(value):
        title = value.strip()
        if ' Ep ' in title: return title.split(' Ep ',1)[0].strip()
        match = re.search(r'\s\d{1,3}$', title)
        if match:
            prefix = title[:match.start()]
            if not prefix.rstrip().lower().endswith('season') and not re.search(r'(?:x|-)\s*$', prefix, re.I):
                return prefix.strip()
        return title

    def hanime_page(self, number):
        if self.hits is None:
            path = Path(self.config['snapshot_file'])
            raw = path.read_bytes() if path.exists() else self.read('1')
            data = json.loads(raw)
            hits = data.get('data')
            if not isinstance(hits, list) or not hits:
                raise ValueError('hanime.tv API returned no complete search snapshot')
            if not path.exists():
                temporary = path.with_suffix('.tmp');temporary.write_bytes(raw);temporary.replace(path)
            metric = 'likes' if self.config['listing'] == 'popular' else 'created_at_unix'
            groups = {}
            for hit in sorted(hits, key=lambda h: h.get(metric) or 0, reverse=True):
                name, slug = self.series_title(hit.get('name','')), hit.get('slug') or ''
                if not name or not re.fullmatch(r'[A-Za-z0-9_-]+', slug):
                    raise ValueError('hanime.tv search entry has invalid canonical metadata')
                groups.setdefault(name, hit)
            self.hits = list(groups.items())
        start = (number - 1) * 24
        if start >= len(self.hits): raise ValueError('hanime.tv page is outside its persisted snapshot')
        entries = []
        for name, hit in self.hits[start:start+24]:
            entry = Entry('/videos/hentai/' + hit['slug'], name, author=hit.get('brand') or '',
                          description=re.sub('<[^>]*>', '', hit.get('description') or ''),
                          thumbnail=hit.get('cover_url') or '', genres=tuple(hit.get('tags') or ()))
            entry.validate();entries.append(entry)
        return Page(True, tuple(entries), str(number+1) if start+24 < len(self.hits) else None)
