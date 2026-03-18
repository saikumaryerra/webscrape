package com.webscrape.recipemd.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

object RecipeParser {

    private const val TAG = "RecipeParser"

    fun parse(html: String, url: String): Recipe? {
        val doc = Jsoup.parse(html)

        // Try JSON-LD first
        val jsonLdRecipe = parseJsonLd(doc, url)
        if (jsonLdRecipe != null) {
            Log.i(TAG, "Parsed via JSON-LD: ${jsonLdRecipe.title}")
            return jsonLdRecipe
        }

        // Fallback to HTML (WPRM classes)
        val htmlRecipe = parseHtml(doc, url)
        if (htmlRecipe != null) {
            Log.i(TAG, "Parsed via HTML: ${htmlRecipe.title}")
            return htmlRecipe
        }

        Log.w(TAG, "Could not parse recipe from $url")
        return null
    }

    private fun parseJsonLd(doc: org.jsoup.nodes.Document, url: String): Recipe? {
        for (script in doc.select("script[type=application/ld+json]")) {
            try {
                val json = script.data()
                val recipeData = findRecipeObject(json) ?: continue
                return mapJsonLdToRecipe(recipeData, url)
            } catch (e: Exception) {
                Log.d(TAG, "JSON-LD parse error: ${e.message}")
            }
        }
        return null
    }

    private fun findRecipeObject(json: String): JSONObject? {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("@type") == "Recipe") return obj
            }
            return null
        }

        val obj = JSONObject(trimmed)

        // Direct Recipe object
        if (obj.optString("@type") == "Recipe") return obj

        // @graph wrapper
        val graph = obj.optJSONArray("@graph")
        if (graph != null) {
            for (i in 0 until graph.length()) {
                val item = graph.getJSONObject(i)
                if (item.optString("@type") == "Recipe") return item
            }
        }
        return null
    }

    private fun mapJsonLdToRecipe(data: JSONObject, url: String): Recipe {
        return Recipe(
            title = data.optString("name", ""),
            description = stripHtml(data.optString("description", "")),
            url = url,
            imageUrl = extractImage(data),
            prepTime = parseIsoDuration(data.optString("prepTime", "")),
            cookTime = parseIsoDuration(data.optString("cookTime", "")),
            totalTime = parseIsoDuration(data.optString("totalTime", "")),
            servings = extractServings(data),
            ingredients = extractStringList(data, "recipeIngredient"),
            instructions = extractInstructions(data),
            nutrition = extractNutrition(data.optJSONObject("nutrition")),
            categories = extractCommaSeparated(data, "recipeCategory"),
            cuisine = extractCommaSeparated(data, "recipeCuisine"),
            keywords = extractCommaSeparated(data, "keywords"),
        )
    }

    private fun extractImage(data: JSONObject): String? {
        val image = data.opt("image") ?: return null
        return when (image) {
            is String -> image
            is JSONArray -> if (image.length() > 0) image.getString(0) else null
            is JSONObject -> image.optString("url")
            else -> null
        }
    }

    private fun extractServings(data: JSONObject): String? {
        val yield_ = data.opt("recipeYield") ?: return null
        return when (yield_) {
            is JSONArray -> if (yield_.length() > 0) yield_.getString(0) else null
            is String -> yield_
            else -> yield_.toString()
        }
    }

    private fun extractStringList(data: JSONObject, key: String): List<String> {
        val arr = data.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun extractInstructions(data: JSONObject): List<String> {
        val instructions = data.opt("recipeInstructions") ?: return emptyList()
        val steps = mutableListOf<String>()

        when (instructions) {
            is String -> {
                stripHtml(instructions).split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .let { steps.addAll(it) }
            }
            is JSONArray -> {
                for (i in 0 until instructions.length()) {
                    when (val item = instructions.get(i)) {
                        is String -> steps.add(stripHtml(item))
                        is JSONObject -> {
                            when (item.optString("@type")) {
                                "HowToStep" -> steps.add(stripHtml(item.optString("text", "")))
                                "HowToSection" -> {
                                    val name = item.optString("name", "")
                                    if (name.isNotEmpty()) steps.add("**$name**")
                                    val subItems = item.optJSONArray("itemListElement")
                                    if (subItems != null) {
                                        for (j in 0 until subItems.length()) {
                                            val sub = subItems.optJSONObject(j)
                                            if (sub != null) {
                                                steps.add(stripHtml(sub.optString("text", "")))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return steps.filter { it.isNotEmpty() }
    }

    private fun extractNutrition(nutrition: JSONObject?): Map<String, String> {
        if (nutrition == null) return emptyMap()
        val fields = mapOf(
            "calories" to "Calories",
            "fatContent" to "Fat",
            "saturatedFatContent" to "Saturated Fat",
            "carbohydrateContent" to "Carbohydrates",
            "sugarContent" to "Sugar",
            "fiberContent" to "Fiber",
            "proteinContent" to "Protein",
            "sodiumContent" to "Sodium",
            "cholesterolContent" to "Cholesterol",
        )
        val result = mutableMapOf<String, String>()
        for ((key, label) in fields) {
            val value = nutrition.optString(key, "")
            if (value.isNotEmpty()) result[label] = value
        }
        return result
    }

    private fun extractCommaSeparated(data: JSONObject, key: String): List<String> {
        val value = data.opt(key) ?: return emptyList()
        return when (value) {
            is JSONArray -> (0 until value.length()).map { value.getString(it).trim() }
            is String -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun parseHtml(doc: org.jsoup.nodes.Document, url: String): Recipe? {
        val title = (doc.selectFirst(".wprm-recipe-name")
            ?: doc.selectFirst("h1.entry-title")
            ?: doc.selectFirst("h1"))
            ?.text()?.trim() ?: return null

        return Recipe(
            title = title,
            description = doc.selectFirst(".wprm-recipe-summary")?.text()?.trim() ?: "",
            url = url,
            imageUrl = doc.selectFirst(".wprm-recipe-image img")
                ?.let { it.attr("src").ifEmpty { it.attr("data-src") } },
            prepTime = doc.selectFirst(".wprm-recipe-prep_time-container")?.text()?.trim(),
            cookTime = doc.selectFirst(".wprm-recipe-cook_time-container")?.text()?.trim(),
            totalTime = doc.selectFirst(".wprm-recipe-total_time-container")?.text()?.trim(),
            servings = doc.selectFirst(".wprm-recipe-servings")?.text()?.trim(),
            ingredients = doc.select(".wprm-recipe-ingredient").map { it.text().trim() }
                .filter { it.isNotEmpty() },
            instructions = doc.select(".wprm-recipe-instruction").map { el ->
                val textEl = el.selectFirst(".wprm-recipe-instruction-text")
                (textEl ?: el).text().trim()
            }.filter { it.isNotEmpty() },
        )
    }

    private fun stripHtml(text: String): String {
        if (text.isBlank()) return ""
        return Jsoup.parse(text).text().trim()
    }

    private fun parseIsoDuration(iso: String): String? {
        if (iso.isBlank()) return null
        val regex = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
        val match = regex.find(iso) ?: return iso
        val (h, m, s) = match.destructured
        val parts = mutableListOf<String>()
        if (h.isNotEmpty()) parts.add("${h} hr${if (h.toInt() > 1) "s" else ""}")
        if (m.isNotEmpty()) parts.add("${m} min")
        if (s.isNotEmpty()) parts.add("${s} sec")
        return parts.joinToString(" ").ifEmpty { null }
    }
}
