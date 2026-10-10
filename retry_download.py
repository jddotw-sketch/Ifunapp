"""Bounded retries for fetching media; saving happens once, after fetch succeeds."""
import time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import requests
from downloader import DownloadError, fetch_post

# Four total attempts, with three retries. Never retry forever.
RETRY_DELAYS = (3, 8, 20)
RETRY_HTTP_CODES = {408, 425, 429, 500, 502, 503, 504}


def should_retry(error):
    if isinstance(error, requests.HTTPError):
        response = error.response
        return response is not None and response.status_code in RETRY_HTTP_CODES
    return isinstance(error, (requests.Timeout, requests.ConnectionError,
                              requests.exceptions.ChunkedEncodingError,
                              requests.exceptions.ContentDecodingError, DownloadError))


def retry_after(error, default):
    """Use server Retry-After when available, capped at one minute per retry."""
    response = getattr(error, 'response', None)
    value = response.headers.get('Retry-After') if response is not None else None
    if value:
        try:
            delay = float(value)
        except (TypeError, ValueError):
            try:
                target = parsedate_to_datetime(value)
                if target.tzinfo is None:
                    target = target.replace(tzinfo=timezone.utc)
                delay = (target - datetime.now(timezone.utc)).total_seconds()
            except (TypeError, ValueError, OverflowError):
                return default
        # A malformed number must not cause an infinite/NaN sleep.
        if not (0 <= delay < float('inf')):
            return default
        return min(60, max(default, delay))
    return default


def fetch_with_retries(url, progress, *, fetch=None, sleep=time.sleep):
    fetch = fetch or fetch_post
    attempts = len(RETRY_DELAYS) + 1
    for index in range(attempts):
        progress(f'Downloading — attempt {index + 1}/{attempts}')
        try:
            return fetch(url, crop_logo=True)
        except Exception as error:
            if index == attempts - 1 or not should_retry(error):
                raise  # Preserve the actual failure for the result notification.
            delay = retry_after(error, RETRY_DELAYS[index])
            progress(f'Download interrupted. Retrying in {delay:g}s…\n'
                     f'Next attempt: {index + 2}/{attempts}')
            sleep(delay)
