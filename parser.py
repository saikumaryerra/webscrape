import json
import logging
import re

from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)


def _parse_iso_duration(duration):
    """Convert ISO 8601 duration (PT30M, PT1H15M) to human-readable string."""
    if not duration:
        return None
    match = re.match(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", duration)
    if not match:
        return duration
    hours, minutes, seconds = match.groups()
    parts = []
    if hours:
        parts.append(f"{hours} hr{'s' if int(hours) > 1 else ''}")
    if minutes:
        parts.append(f"{minutes} min")
    if seconds:
        parts.append(f"{seconds} sec")
    return " ".join(parts) if parts else duration


def _strip_html(text):
    """Remove HTML tags from a string."""
    if not text:
        return ""
    return BeautifulSoup(text, "lxml").get_text(strip=True)


def _extract_json_ld(soup):
    """Extract Recipe schema from JSON-LD scripts."""
    for script in soup.find_all("script", type="application/ld+json"):
        try:
            data = json.loads(script.string)
        except (json.JSONDecodeError, TypeError):
            continue

        # Handle @graph wrapper
        if isinstance(data, dict) and "@graph" in data:
            data = data["@graph"]

        if isinstance(data, list):
            for item in data:
                if isinstance(item, dict) and item.get("@type") == "Recipe":
                    return item
        elif isinstance(data, dict) and data.get("@type") == "Recipe":
            return data

    return None


def _parse_instructions(instructions):
    """Normalize recipeInstructions to a list of step strings."""
    if not instructions:
        return []

    steps = []

    if isinstance(instructions, str):
        # Plain text or HTML string
        text = _strip_html(instructions)
        return [s.strip() for s in text.split("\n") if s.strip()]

    if isinstance(instructions, list):
        for item in instructions:
            if isinstance(item, str):
                steps.append(_strip_html(item))
            elif isinstance(item, dict):
                if item.get("@type") == "HowToStep":
                    steps.append(_strip_html(item.get("text", "")))
                elif item.get("@type") == "HowToSection":
                    # Section with sub-steps
                    section_name = item.get("name", "")
                    if section_name:
                        steps.append(f"**{section_name}**")
                    for sub_item in item.get("itemListElement", []):
                        if isinstance(sub_item, dict):
                            steps.append(_strip_html(sub_item.get("text", "")))

    return [s for s in steps if s]


def _parse_nutrition(nutrition):
    """Extract nutrition info from schema data."""
    if not nutrition or not isinstance(nutrition, dict):
        return {}

    fields = {
        "calories": "Calories",
        "fatContent": "Fat",
        "saturatedFatContent": "Saturated Fat",
        "carbohydrateContent": "Carbohydrates",
        "sugarContent": "Sugar",
        "fiberContent": "Fiber",
        "proteinContent": "Protein",
        "sodiumContent": "Sodium",
        "cholesterolContent": "Cholesterol",
    }

    result = {}
    for key, label in fields.items():
        value = nutrition.get(key)
        if value:
            result[label] = str(value)
    return result


def parse_recipe_jsonld(soup, url):
    """Parse recipe from JSON-LD structured data."""
    data = _extract_json_ld(soup)
    if not data:
        return None

    recipe = {
        "title": data.get("name", ""),
        "description": _strip_html(data.get("description", "")),
        "url": url,
        "image": None,
        "prep_time": _parse_iso_duration(data.get("prepTime")),
        "cook_time": _parse_iso_duration(data.get("cookTime")),
        "total_time": _parse_iso_duration(data.get("totalTime")),
        "servings": data.get("recipeYield"),
        "ingredients": data.get("recipeIngredient", []),
        "instructions": _parse_instructions(data.get("recipeInstructions")),
        "nutrition": _parse_nutrition(data.get("nutrition")),
        "categories": data.get("recipeCategory", []),
        "cuisine": data.get("recipeCuisine", []),
        "keywords": data.get("keywords", ""),
    }

    # Normalize servings
    if isinstance(recipe["servings"], list):
        recipe["servings"] = recipe["servings"][0] if recipe["servings"] else None

    # Normalize image
    image = data.get("image")
    if isinstance(image, list):
        recipe["image"] = image[0] if image else None
    elif isinstance(image, dict):
        recipe["image"] = image.get("url")
    elif isinstance(image, str):
        recipe["image"] = image

    # Normalize categories/cuisine to lists
    for field in ("categories", "cuisine"):
        if isinstance(recipe[field], str):
            recipe[field] = [s.strip() for s in recipe[field].split(",")]

    # Normalize keywords
    if isinstance(recipe["keywords"], str):
        recipe["keywords"] = [k.strip() for k in recipe["keywords"].split(",") if k.strip()]

    return recipe


def parse_recipe_html(soup, url):
    """Fallback: parse recipe from WPRM HTML classes."""
    recipe = {
        "title": "",
        "description": "",
        "url": url,
        "image": None,
        "prep_time": None,
        "cook_time": None,
        "total_time": None,
        "servings": None,
        "ingredients": [],
        "instructions": [],
        "nutrition": {},
        "categories": [],
        "cuisine": [],
        "keywords": [],
    }

    # Title
    title_el = soup.select_one(".wprm-recipe-name") or soup.select_one("h1.entry-title") or soup.find("h1")
    if title_el:
        recipe["title"] = title_el.get_text(strip=True)

    # Description
    desc_el = soup.select_one(".wprm-recipe-summary")
    if desc_el:
        recipe["description"] = desc_el.get_text(strip=True)

    # Time fields
    for field, css_class in [
        ("prep_time", ".wprm-recipe-prep_time-container"),
        ("cook_time", ".wprm-recipe-cook_time-container"),
        ("total_time", ".wprm-recipe-total_time-container"),
    ]:
        el = soup.select_one(css_class)
        if el:
            recipe[field] = el.get_text(strip=True)

    # Servings
    servings_el = soup.select_one(".wprm-recipe-servings")
    if servings_el:
        recipe["servings"] = servings_el.get_text(strip=True)

    # Ingredients
    for li in soup.select(".wprm-recipe-ingredient"):
        text = li.get_text(strip=True)
        if text:
            recipe["ingredients"].append(text)

    # Instructions
    for li in soup.select(".wprm-recipe-instruction"):
        text_el = li.select_one(".wprm-recipe-instruction-text")
        text = text_el.get_text(strip=True) if text_el else li.get_text(strip=True)
        if text:
            recipe["instructions"].append(text)

    # Image
    img = soup.select_one(".wprm-recipe-image img")
    if img:
        recipe["image"] = img.get("src") or img.get("data-src")

    return recipe if recipe["title"] else None


def parse_recipe(html, url):
    """Parse a recipe page. Tries JSON-LD first, falls back to HTML parsing."""
    soup = BeautifulSoup(html, "lxml")

    recipe = parse_recipe_jsonld(soup, url)
    if recipe and recipe.get("title"):
        logger.info(f"Parsed recipe via JSON-LD: {recipe['title']}")
        return recipe

    logger.info("JSON-LD not found, falling back to HTML parsing")
    recipe = parse_recipe_html(soup, url)
    if recipe:
        logger.info(f"Parsed recipe via HTML: {recipe['title']}")
    else:
        logger.warning(f"Could not parse recipe from {url}")

    return recipe
