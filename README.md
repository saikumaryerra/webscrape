# webscrape

Web scraper that extracts recipes from [indianhealthyrecipes.com](https://www.indianhealthyrecipes.com/) and saves them as structured Markdown files.

## Setup

Requires Python 3.11+ and [uv](https://docs.astral.sh/uv/).

```bash
uv sync
```

## Usage

**Scrape the entire website** (discovers all recipes via sitemap):

```bash
uv run python scraper.py --sitemap
```

**Scrape specific URLs:**

```bash
uv run python scraper.py https://www.indianhealthyrecipes.com/palak-paneer-recipe/
```

**Scrape URLs from a file** (one URL per line):

```bash
uv run python scraper.py -f urls.txt
```

**Combine sources:**

```bash
uv run python scraper.py -f urls.txt --sitemap https://www.indianhealthyrecipes.com/extra-recipe/
```

### Options

| Flag | Description | Default |
|---|---|---|
| `-o`, `--output` | Output directory | `./output` |
| `--delay` | Seconds between requests | `1.0` |
| `--limit` | Max number of recipes to scrape | unlimited |
| `--sitemap` | Discover all recipe URLs from sitemap | off |
| `--sitemap-url` | Custom base URL for sitemap | indianhealthyrecipes.com |
| `-v`, `--verbose` | Enable debug logging | off |

### Examples

```bash
# Scrape 10 recipes from sitemap into a custom directory
uv run python scraper.py --sitemap --limit 10 -o recipes/

# Scrape with a longer delay to be polite
uv run python scraper.py -f urls.txt --delay 3.0

# Verbose output for debugging
uv run python scraper.py -v https://www.indianhealthyrecipes.com/mango-lassi-recipe/
```

## Output Format

Each recipe is saved as a Markdown file (e.g. `palak-paneer.md`) with:

- Title and description
- Image
- Prep/cook/total time and servings
- Ingredients list
- Numbered instructions
- Nutrition table
- Source URL, categories, cuisine, and keywords

## How It Works

1. **Fetching** (`fetcher.py`) -- Uses `curl_cffi` to impersonate Chrome's TLS fingerprint (bypasses Cloudflare), with retry and exponential backoff
2. **Parsing** (`parser.py`) -- Extracts recipe data from JSON-LD structured data (primary) or WPRM HTML classes (fallback)
3. **Writing** (`markdown_writer.py`) -- Converts recipe data to Markdown and saves to disk

Failed URLs are logged and skipped; a summary is printed at the end.

## Project Structure

```
├── pyproject.toml       # Dependencies and project config
├── uv.lock              # Lock file
├── scraper.py           # CLI entry point
├── fetcher.py           # HTTP fetching + sitemap discovery
├── parser.py            # Recipe extraction (JSON-LD + HTML fallback)
├── markdown_writer.py   # Markdown formatting + file output
└── output/              # Default output directory
```
