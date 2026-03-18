import re
import time
import logging
import xml.etree.ElementTree as ET

import requests
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept-Encoding": "gzip, deflate, br",
    "DNT": "1",
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


def _is_recipe_url(url, base_url):
    """Check if a URL looks like a recipe page (not a category/tag/archive)."""
    path = url.replace(base_url, "").strip("/")
    if not path:
        return False
    skip_words = [
        "category", "tag", "page", "author", "wp-content",
        "recipes", "about", "contact", "privacy", "disclaimer",
        "sitemap", "feed",
    ]
    skip_extensions = (".xml", ".jpg", ".png", ".gif", ".css", ".js")
    path_lower = path.lower()
    if path_lower in skip_words:
        return False
    if any(path_lower.startswith(w + "/") for w in skip_words):
        return False
    if any(path_lower.endswith(ext) for ext in skip_extensions):
        return False
    # Recipe URLs are typically single-level paths like /palak-paneer-recipe/
    if "/" in path:
        return False
    return True


def _parse_sitemap_xml(content, session, base_url):
    """Try to parse content as XML sitemap. Returns list of recipe URLs."""
    try:
        root = ET.fromstring(content)
    except ET.ParseError:
        return None

    ns = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
    all_urls = []

    # Check if this is a sitemap index (contains other sitemaps)
    sub_sitemaps = root.findall(".//sm:sitemap/sm:loc", ns)
    if sub_sitemaps:
        logger.info(f"Found sitemap index with {len(sub_sitemaps)} child sitemaps")
        for loc in sub_sitemaps:
            sub_url = loc.text.strip()
            if "post-sitemap" in sub_url:
                logger.info(f"Fetching child sitemap: {sub_url}")
                sub_content = fetch_page(sub_url, session, retries=2)
                if sub_content:
                    try:
                        sub_root = ET.fromstring(sub_content)
                        for url_loc in sub_root.findall(".//sm:url/sm:loc", ns):
                            all_urls.append(url_loc.text.strip())
                    except ET.ParseError:
                        continue
        return all_urls

    # Regular sitemap with URLs
    url_locs = root.findall(".//sm:url/sm:loc", ns)
    for loc in url_locs:
        all_urls.append(loc.text.strip())

    return all_urls


def _parse_sitemap_html(content, base_url):
    """Parse an HTML sitemap page for recipe links."""
    soup = BeautifulSoup(content, "lxml")
    urls = []
    for a in soup.find_all("a", href=True):
        href = a["href"]
        if href.startswith(base_url) and _is_recipe_url(href, base_url):
            urls.append(href)
    return urls


def _crawl_recipe_index(base_url, session):
    """Crawl the /recipes/ index page and category pages to discover recipe URLs."""
    index_url = f"{base_url}/recipes/"
    logger.info(f"Crawling recipe index: {index_url}")
    content = fetch_page(index_url, session, retries=2)
    if not content:
        return []

    soup = BeautifulSoup(content, "lxml")
    recipe_urls = set()
    category_urls = set()

    # Collect recipe URLs and category page URLs from the index
    for a in soup.find_all("a", href=True):
        href = a["href"].rstrip("/") + "/"
        if not href.startswith(base_url):
            continue
        if _is_recipe_url(href, base_url):
            recipe_urls.add(href)
        elif "/recipes/" in href and href != index_url:
            category_urls.add(href)

    logger.info(f"Found {len(recipe_urls)} recipes and {len(category_urls)} category pages from index")

    # Crawl each category page for more recipe URLs
    for i, cat_url in enumerate(sorted(category_urls)):
        logger.info(f"Crawling category [{i+1}/{len(category_urls)}]: {cat_url}")
        time.sleep(1)
        cat_content = fetch_page(cat_url, session, retries=2)
        if not cat_content:
            continue
        cat_soup = BeautifulSoup(cat_content, "lxml")
        for a in cat_soup.find_all("a", href=True):
            href = a["href"].rstrip("/") + "/"
            if href.startswith(base_url) and _is_recipe_url(href, base_url):
                recipe_urls.add(href)

        # Check for pagination within category
        page_num = 2
        while True:
            next_link = cat_soup.find("a", href=re.compile(rf"{re.escape(cat_url)}page/\d+"))
            if not next_link:
                break
            next_url = f"{cat_url}page/{page_num}/"
            logger.debug(f"  Pagination: {next_url}")
            time.sleep(1)
            next_content = fetch_page(next_url, session, retries=1)
            if not next_content:
                break
            cat_soup = BeautifulSoup(next_content, "lxml")
            found_new = False
            for a in cat_soup.find_all("a", href=True):
                href = a["href"].rstrip("/") + "/"
                if href.startswith(base_url) and _is_recipe_url(href, base_url):
                    if href not in recipe_urls:
                        found_new = True
                    recipe_urls.add(href)
            if not found_new:
                break
            page_num += 1

    return list(recipe_urls)


def fetch_sitemap(base_url, session=None):
    """Discover recipe URLs from the site.

    Tries XML sitemaps first, then HTML sitemap, then crawls the recipe index.
    Returns a list of URLs that look like recipe pages.
    """
    if session is None:
        session = create_session()

    base_url = base_url.rstrip("/")

    # Strategy 1: Try XML sitemaps
    sitemap_urls = [
        f"{base_url}/sitemap_index.xml",
        f"{base_url}/sitemap.xml",
        f"{base_url}/post-sitemap.xml",
    ]

    for sitemap_url in sitemap_urls:
        logger.info(f"Trying sitemap: {sitemap_url}")
        content = fetch_page(sitemap_url, session, retries=2)
        if content is None:
            continue

        # Try XML parsing first
        xml_urls = _parse_sitemap_xml(content, session, base_url)
        if xml_urls:
            recipe_urls = [u for u in xml_urls if _is_recipe_url(u, base_url)]
            if recipe_urls:
                logger.info(f"Found {len(recipe_urls)} recipe URLs from XML sitemap")
                return recipe_urls

        # Try parsing as HTML sitemap
        html_urls = _parse_sitemap_html(content, base_url)
        if html_urls:
            logger.info(f"Found {len(html_urls)} recipe URLs from HTML sitemap")
            return html_urls

        logger.debug(f"No recipe URLs found in {sitemap_url}")

    # Strategy 2: Crawl the recipe index pages
    logger.info("Sitemap parsing failed, falling back to recipe index crawl")
    recipe_urls = _crawl_recipe_index(base_url, session)
    if recipe_urls:
        logger.info(f"Found {len(recipe_urls)} recipe URLs from index crawl")
        return recipe_urls

    logger.warning("Could not discover any recipe URLs")
    return []
