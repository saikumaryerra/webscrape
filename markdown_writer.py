import os
import re
import logging

logger = logging.getLogger(__name__)


def _sanitize_filename(title):
    """Convert a recipe title to a safe filename."""
    name = title.lower().strip()
    name = re.sub(r"[^\w\s-]", "", name)
    name = re.sub(r"[\s_]+", "-", name)
    name = re.sub(r"-+", "-", name)
    return name[:80].strip("-")


def recipe_to_markdown(recipe):
    """Convert a recipe dict to a Markdown string."""
    lines = []

    # Title
    lines.append(f"# {recipe['title']}\n")

    # Description
    if recipe.get("description"):
        lines.append(f"{recipe['description']}\n")

    # Image
    if recipe.get("image"):
        lines.append(f"![{recipe['title']}]({recipe['image']})\n")

    # Time and servings table
    time_rows = []
    for label, key in [
        ("Prep Time", "prep_time"),
        ("Cook Time", "cook_time"),
        ("Total Time", "total_time"),
        ("Servings", "servings"),
    ]:
        value = recipe.get(key)
        if value:
            time_rows.append(f"| {label} | {value} |")

    if time_rows:
        lines.append("| | |")
        lines.append("|---|---|")
        lines.extend(time_rows)
        lines.append("")

    # Ingredients
    if recipe.get("ingredients"):
        lines.append("## Ingredients\n")
        for ing in recipe["ingredients"]:
            lines.append(f"- {ing}")
        lines.append("")

    # Instructions
    if recipe.get("instructions"):
        lines.append("## Instructions\n")
        step_num = 1
        for step in recipe["instructions"]:
            if step.startswith("**") and step.endswith("**"):
                # Section header
                lines.append(f"\n### {step.strip('*')}\n")
                step_num = 1
            else:
                lines.append(f"{step_num}. {step}")
                step_num += 1
        lines.append("")

    # Nutrition
    if recipe.get("nutrition"):
        lines.append("## Nutrition\n")
        lines.append("| Nutrient | Amount |")
        lines.append("|---|---|")
        for nutrient, amount in recipe["nutrition"].items():
            lines.append(f"| {nutrient} | {amount} |")
        lines.append("")

    # Footer metadata
    lines.append("---")
    if recipe.get("url"):
        lines.append(f"*Source: {recipe['url']}*  ")
    if recipe.get("categories"):
        lines.append(f"*Categories: {', '.join(recipe['categories'])}*  ")
    if recipe.get("cuisine"):
        lines.append(f"*Cuisine: {', '.join(recipe['cuisine'])}*  ")
    if recipe.get("keywords"):
        lines.append(f"*Keywords: {', '.join(recipe['keywords'])}*")
    lines.append("")

    return "\n".join(lines)


def save_recipe(recipe, output_dir):
    """Save a recipe as a Markdown file.

    Returns the file path on success, None on failure.
    """
    if not recipe or not recipe.get("title"):
        logger.warning("Cannot save recipe without a title")
        return None

    os.makedirs(output_dir, exist_ok=True)

    filename = _sanitize_filename(recipe["title"]) + ".md"
    filepath = os.path.join(output_dir, filename)

    md_content = recipe_to_markdown(recipe)

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(md_content)

    logger.info(f"Saved: {filepath}")
    return filepath
