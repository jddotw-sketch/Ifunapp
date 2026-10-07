"""iFunny Open Graph downloader, preserving the optional 20 pixel crop."""
import io
import mimetypes
import re
from pathlib import Path
from urllib.parse import urlparse, urljoin
import requests
from bs4 import BeautifulSoup
from PIL import Image

USER_AGENT = 'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36'
URL_RE = re.compile(r'https?://[^\s<>"\x27]+', re.IGNORECASE)


class DownloadError(RuntimeError):
    pass


def extract_ifunny_url(text):
    for candidate in URL_RE.findall(text or ''):
        url = candidate.rstrip('.,;:!?)]}"')
        host = (urlparse(url).hostname or '').lower()
        if host == 'ifunny.co' or host.endswith('.ifunny.co'):
            return url
    raise DownloadError('No iFunny link was found in the shared text.')


def get_meta(soup, *properties):
    for prop in properties:
        tag = soup.find('meta', attrs={'property': prop})
        if tag and tag.get('content'):
            return str(tag['content']).strip()
    return None


def full_quality_image_url(url):
    if urlparse(url).hostname == 'imageproxy.getfn.io':
        match = re.search(r'/images/([^/?#]+)', urlparse(url).path)
        if match:
            return 'https://img.getfn.io/images/' + match.group(1)
    return url


def resolve_media(post_url, session):
    response = session.get(post_url, timeout=(10, 30))
    response.raise_for_status()
    soup = BeautifulSoup(response.text, 'html.parser')
    video = get_meta(soup, 'og:video:secure_url', 'og:video:url', 'og:video')
    if video:
        return urljoin(response.url, video), 'video'
    image = get_meta(soup, 'og:image:secure_url', 'og:image')
    if image:
        return full_quality_image_url(urljoin(response.url, image)), 'image'
    for tag in soup.find_all('img'):
        src = str(tag.get('src', ''))
        if 'getfn.io/images/' in src:
            return full_quality_image_url(urljoin(response.url, src)), 'image'
    raise DownloadError('Could not locate downloadable media on this post.')


def safe_stem(url):
    stem = urlparse(url).path.rstrip('/').rsplit('/', 1)[-1] or 'post'
    return re.sub(r'[^A-Za-z0-9._-]+', '_', stem).strip('._')[-120:] or 'post'


def crop_bottom(data, pixels=20):
    with Image.open(io.BytesIO(data)) as image:
        fmt = (image.format or 'JPEG').upper()
        if image.height <= pixels or getattr(image, 'is_animated', False):
            return data, fmt
        cropped = image.crop((0, 0, image.width, image.height - pixels))
        output = io.BytesIO()
        options = {'quality': 95, 'optimize': True} if fmt in ('JPEG', 'JPG') else {}
        cropped.save(output, format=fmt, **options)
        return output.getvalue(), fmt


def fetch_post(post_url, crop_logo=True):
    with requests.Session() as session:
        session.headers.update({'User-Agent': USER_AGENT, 'Accept-Language': 'en-US,en;q=0.9'})
        media_url, kind = resolve_media(post_url, session)
        response = session.get(media_url, headers={'Referer': post_url}, timeout=(10, 90))
        response.raise_for_status()
        data = response.content
        if not data:
            raise DownloadError('The media server returned an empty file.')
        mime = response.headers.get('Content-Type', '').split(';')[0].strip().lower()
        if mime in ('text/html', 'application/json'):
            raise DownloadError('The media server returned a page instead of media.')
        ext = Path(urlparse(media_url).path).suffix.lower()
        if not ext or len(ext) > 5:
            ext = mimetypes.guess_extension(mime) or ('.mp4' if kind == 'video' else '.jpg')
        if kind == 'image' and crop_logo:
            try:
                data, fmt = crop_bottom(data)
                formats = {'JPEG': ('.jpg', 'image/jpeg'), 'PNG': ('.png', 'image/png'),
                           'WEBP': ('.webp', 'image/webp'), 'GIF': ('.gif', 'image/gif')}
                ext, mime = formats.get(fmt, (ext, mime))
            except Exception:
                pass  # Preserve the original image if Pillow cannot process it.
        if not mime or mime == 'application/octet-stream':
            mime = mimetypes.guess_type('file' + ext)[0] or ('video/mp4' if kind == 'video' else 'image/jpeg')
        return data, 'ifunny_' + safe_stem(post_url) + ext, mime
