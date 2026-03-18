package com.webscrape.recipemd.data

import android.content.Context
import android.util.Log
import java.io.File

object MarkdownWriter {

    private const val TAG = "MarkdownWriter"

    fun toMarkdown(recipe: Recipe): String = buildString {
        appendLine("# ${recipe.title}")
        appendLine()

        if (recipe.description.isNotEmpty()) {
            appendLine(recipe.description)
            appendLine()
        }

        if (recipe.imageUrl != null) {
            appendLine("![${recipe.title}](${recipe.imageUrl})")
            appendLine()
        }

        // Time and servings table
        val timeRows = mutableListOf<Pair<String, String>>()
        recipe.prepTime?.let { timeRows.add("Prep Time" to it) }
        recipe.cookTime?.let { timeRows.add("Cook Time" to it) }
        recipe.totalTime?.let { timeRows.add("Total Time" to it) }
        recipe.servings?.let { timeRows.add("Servings" to it) }
        if (timeRows.isNotEmpty()) {
            appendLine("| | |")
            appendLine("|---|---|")
            for ((label, value) in timeRows) {
                appendLine("| $label | $value |")
            }
            appendLine()
        }

        // Ingredients
        if (recipe.ingredients.isNotEmpty()) {
            appendLine("## Ingredients")
            appendLine()
            for (ing in recipe.ingredients) {
                appendLine("- $ing")
            }
            appendLine()
        }

        // Instructions
        if (recipe.instructions.isNotEmpty()) {
            appendLine("## Instructions")
            appendLine()
            var stepNum = 1
            for (step in recipe.instructions) {
                if (step.startsWith("**") && step.endsWith("**")) {
                    appendLine()
                    appendLine("### ${step.trim('*')}")
                    appendLine()
                    stepNum = 1
                } else {
                    appendLine("$stepNum. $step")
                    stepNum++
                }
            }
            appendLine()
        }

        // Nutrition
        if (recipe.nutrition.isNotEmpty()) {
            appendLine("## Nutrition")
            appendLine()
            appendLine("| Nutrient | Amount |")
            appendLine("|---|---|")
            for ((nutrient, amount) in recipe.nutrition) {
                appendLine("| $nutrient | $amount |")
            }
            appendLine()
        }

        // Footer
        appendLine("---")
        if (recipe.url.isNotEmpty()) appendLine("*Source: ${recipe.url}*  ")
        if (recipe.categories.isNotEmpty()) appendLine("*Categories: ${recipe.categories.joinToString(", ")}*  ")
        if (recipe.cuisine.isNotEmpty()) appendLine("*Cuisine: ${recipe.cuisine.joinToString(", ")}*  ")
        if (recipe.keywords.isNotEmpty()) appendLine("*Keywords: ${recipe.keywords.joinToString(", ")}*")
    }

    fun saveRecipe(context: Context, recipe: Recipe): File? {
        val dir = File(context.getExternalFilesDir(null), "recipes")
        dir.mkdirs()

        val filename = sanitizeFilename(recipe.title) + ".md"
        val file = File(dir, filename)

        return try {
            file.writeText(toMarkdown(recipe))
            Log.i(TAG, "Saved: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ${recipe.title}: ${e.message}")
            null
        }
    }

    fun saveHtml(context: Context, html: String, title: String): File? {
        val dir = File(context.getExternalFilesDir(null), "recipes/html")
        dir.mkdirs()

        val filename = sanitizeFilename(title) + ".html"
        val file = File(dir, filename)

        return try {
            file.writeText(html)
            Log.i(TAG, "Saved HTML: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save HTML for $title: ${e.message}")
            null
        }
    }

    private fun sanitizeFilename(title: String): String {
        return title.lowercase()
            .replace(Regex("[^\\w\\s-]"), "")
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("-+"), "-")
            .take(80)
            .trim('-')
    }
}
