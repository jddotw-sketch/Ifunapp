from __future__ import annotations

import io
import mimetypes
import re
from pathlib import Path
from urllib.parse import urlparse

import requests
from bs4 import BeautifulSoup
from PIL import Image

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
)
URL_RE = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)


class DownloadError(RuntimeError):
    pass


def extract_ifunny_url(text: str) -> str:
    for match in URL_RE.findall(text or ""):
        candidate = match.rstrip(".,;:!?)]}\"")
        host = (urlparse(candidate).hostname or "").lower()
        if host == "ifunny.co" or host.endswith(".ifunny.co"):
            return candidate
    raise DownloadError("No iFunny link was found in the shared text.")


def session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": USER_AGENT, "Accept-Language": "en-US,en;q=0.9"})
    return s


def get_meta(soup: BeautifulSoup, *properties: str) -> str | None:
    for prop in properties:
        tag = soup.find("meta", attrs={"property": prop})
        if tag and tag.get("content"):
            return str(tag["content"]).strip()
    return None


def full_quality_image_url(url: str) -> str:
    parsed = urlparse(url)
    if parsed.hostname == "imageproxy.getfn.io":
        match = re.search(r"/images/([^/?#]+)", parsed.path)
        if match:
            return f"https://img.getfn.io/images/{match.group(1)}"
    return url


def resolve_media(post_url: str, s: requests.Session) -> tuple[str, str]:
    response = s.get(post_url, timeout=(10, 30), allow_redirects=True)
    response.raise_for_status()
    soup = BeautifulSoup(response.text, "html.parser")

    video_url = get_meta(soup, "og:video:secure_url", "og:video:url", "og:video")
    if video_url:
        return "video", video_url

    image_url = get_meta(soup, "og:image:secure_url", "og:image")
    if image_url:
        return "image", full_quality_image_url(image_url)

    for image in soup.find_all("img"):
        src = image.get("src")
        if src and ("getfn.io/images/" in src or "/images/" in src):
            return "image", full_quality_image_url(str(src))

    raise DownloadError("Could not locate downloadable media on this post.")


def safe_stem(post_url: str) -> str:
    name = urlparse(post_url).path.rstrip("/").rsplit("/", 1)[-1] or "post"
    name = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._")
    return (name or "post")[-120:]


def extension(media_url: str, response: requests.Response, kind: str) -> str:
    suffix = Path(urlparse(media_url).path).suffix.lower()
    if suffix and 1 < len(suffix) <= 6:
        return suffix
    content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip()
    guessed = mimetypes.guess_extension(content_type) if content_type else None
    return guessed or (".mp4" if kind == "video" else ".jpg")


def crop_bottom(data: bytes, pixels: int = 20) -> tuple[bytes, str | None]:
    with Image.open(io.BytesIO(data)) as image:
        if image.height <= pixels:
            return data, image.format
        cropped = image.crop((0, 0, image.width, image.height - pixels))
        out = io.BytesIO()
        fmt = image.format or "JPEG"
        kwargs = {"quality": 95, "optimize": True} if fmt.upper() in {"JPEG", "JPG"} else {}
        cropped.save(out, format=fmt, **kwargs)
        return out.getvalue(), fmt


def fetch_post(post_url: str, crop_logo: bool = True) -> tuple[bytes, str, str]:
    """Return (bytes, filename, MIME type)."""
    s = session()
    kind, media_url = resolve_media(post_url, s)
    response = s.get(media_url, headers={"Referer": post_url}, timeout=(10, 90))
    response.raise_for_status()
    data = response.content
    ext = extension(media_url, response, kind)

    if kind == "image" and crop_logo:
        try:
            data, fmt = crop_bottom(data, 20)
            fmt_ext = {"JPEG": ".jpg", "PNG": ".png", "WEBP": ".webp", "GIF": ".gif"}.get(
                (fmt or "").upper()
            )
            if fmt_ext:
                ext = fmt_ext
        except Exception:
            pass

    mime = response.headers.get("Content-Type", "").split(";", 1)[0].strip()
    if not mime:
        mime = mimetypes.guess_type("x" + ext)[0] or ("video/mp4" if kind == "video" else "image/jpeg")
    return data, f"ifunny_{safe_stem(post_url)}{ext}", mime
