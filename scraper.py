#!/usr/bin/env python3
"""Web scraper for indianhealthyrecipes.com — extracts recipes to Markdown."""

import argparse
import logging
import sys
import time

from fetcher import create_session, fetch_page, fetch_sitemap
from parser import parse_recipe
from markdown_writer import save_recipe, save_html

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger(__name__)


def collect_urls(args):
    """Gather URLs from CLI args, input file, and/or sitemap."""
    urls = []

    # URLs from positional args
    if args.urls:
        urls.extend(args.urls)

    # URLs from input file
    if args.file:
        try:
            with open(args.file, "r") as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#"):
                        urls.append(line)
            logger.info(f"Loaded {len(urls)} URLs from {args.file}")
        except FileNotFoundError:
            logger.error(f"Input file not found: {args.file}")
            sys.exit(1)

    # URLs from sitemap
    if args.sitemap:
        base = args.sitemap_url or "https://www.indianhealthyrecipes.com"
        logger.info(f"Discovering recipes from sitemap: {base}")
        sitemap_urls = fetch_sitemap(base)
        logger.info(f"Found {len(sitemap_urls)} URLs from sitemap")
        urls.extend(sitemap_urls)

    # Deduplicate while preserving order
    seen = set()
    unique = []
    for url in urls:
        if url not in seen:
            seen.add(url)
            unique.append(url)

    return unique


def main():
    parser = argparse.ArgumentParser(
        description="Scrape recipes from indianhealthyrecipes.com and save as Markdown files.",
        epilog="Examples:\n"
               "  python scraper.py https://www.indianhealthyrecipes.com/palak-paneer-recipe/\n"
               "  python scraper.py -f urls.txt -o recipes/\n"
               "  python scraper.py --sitemap --limit 10\n",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("urls", nargs="*", help="Recipe URLs to scrape")
    parser.add_argument("-f", "--file", help="File with URLs (one per line)")
    parser.add_argument("-o", "--output", default="output", help="Output directory (default: output)")
    parser.add_argument("--sitemap", action="store_true", help="Discover recipes from sitemap")
    parser.add_argument("--sitemap-url", help="Base URL for sitemap (default: indianhealthyrecipes.com)")
    parser.add_argument("--delay", type=float, default=1.0, help="Delay between requests in seconds (default: 1)")
    parser.add_argument("--limit", type=int, help="Max number of recipes to scrape")
    parser.add_argument("-v", "--verbose", action="store_true", help="Enable debug logging")

    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    urls = collect_urls(args)
    if not urls:
        parser.print_help()
        print("\nError: No URLs provided. Pass URLs as arguments, use -f, or --sitemap.")
        sys.exit(1)

    if args.limit:
        urls = urls[:args.limit]

    logger.info(f"Scraping {len(urls)} recipe(s), output → {args.output}/")

    session = create_session()
    succeeded = 0
    failed = 0

    for i, url in enumerate(urls, 1):
        logger.info(f"[{i}/{len(urls)}] {url}")

        html = fetch_page(url, session)
        if html is None:
            failed += 1
            continue

        recipe = parse_recipe(html, url)
        if recipe is None:
            logger.warning(f"Could not extract recipe from {url}")
            failed += 1
            continue

        filepath = save_recipe(recipe, args.output)
        if filepath:
            save_html(html, recipe["title"], args.output)
            succeeded += 1
        else:
            failed += 1

        # Rate limit
        if i < len(urls):
            time.sleep(args.delay)

    print(f"\nDone! {succeeded} succeeded, {failed} failed.")


if __name__ == "__main__":
    main()
