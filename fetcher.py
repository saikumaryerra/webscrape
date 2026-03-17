import time
import logging
import xml.etree.ElementTree as ET

import requests

logger = logging.getLogger(__name__)

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept-Encoding": "gzip, deflate, br",
    "Connection": "keep-alive",
    "Upgrade-Insecure-Requests": "1",
}


def create_session():
    """Create a requests session with browser-like headers."""
    session = requests.Session()
    session.headers.update(HEADERS)
    return session


def fetch_page(url, session=None, retries=3):
    """Fetch a page with retry logic and exponential backoff.

    Returns HTML string or None on failure.
    """
    if session is None:
        session = create_session()

    for attempt in range(retries):
        try:
            response = session.get(url, timeout=30)
            response.raise_for_status()
            return response.text
        except requests.RequestException as e:
            wait = 2 ** attempt
            logger.warning(f"Attempt {attempt + 1}/{retries} failed for {url}: {e}")
            if attempt < retries - 1:
                time.sleep(wait)

    logger.error(f"Failed to fetch {url} after {retries} attempts")
    return None


def fetch_sitemap(base_url, session=None):
    """Discover recipe URLs from the site's sitemap.

    Tries sitemap_index.xml first, then sitemap.xml.
    Returns a list of URLs that look like recipe pages.
    """
    if session is None:
        session = create_session()

    base_url = base_url.rstrip("/")
    sitemap_urls = [
        f"{base_url}/sitemap_index.xml",
        f"{base_url}/sitemap.xml",
        f"{base_url}/post-sitemap.xml",
    ]

    all_urls = []

    for sitemap_url in sitemap_urls:
        html = fetch_page(sitemap_url, session, retries=2)
        if html is None:
            continue

        try:
            root = ET.fromstring(html)
        except ET.ParseError:
            logger.warning(f"Could not parse XML from {sitemap_url}")
            continue

        ns = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}

        # Check if this is a sitemap index (contains other sitemaps)
        sub_sitemaps = root.findall(".//sm:sitemap/sm:loc", ns)
        if sub_sitemaps:
            for loc in sub_sitemaps:
                sub_url = loc.text.strip()
                # Only fetch post sitemaps (where recipes live)
                if "post-sitemap" in sub_url:
                    sub_html = fetch_page(sub_url, session, retries=2)
                    if sub_html:
                        try:
                            sub_root = ET.fromstring(sub_html)
                            for url_loc in sub_root.findall(".//sm:url/sm:loc", ns):
                                all_urls.append(url_loc.text.strip())
                        except ET.ParseError:
                            continue
            if all_urls:
                break

        # Regular sitemap with URLs
        url_locs = root.findall(".//sm:url/sm:loc", ns)
        for loc in url_locs:
            all_urls.append(loc.text.strip())

        if all_urls:
            break

    # Filter to likely recipe pages (exclude category, tag, page URLs)
    recipe_urls = []
    for url in all_urls:
        path = url.replace(base_url, "").strip("/")
        # Skip non-recipe pages
        if any(skip in path for skip in ["category/", "tag/", "page/", "author/", "wp-content/"]):
            continue
        # Must have a path (not just the homepage)
        if path and "/" not in path:
            recipe_urls.append(url)

    logger.info(f"Found {len(recipe_urls)} recipe URLs from sitemap")
    return recipe_urls
