package com.webscrape.recipemd.data

data class Recipe(
    val title: String,
    val description: String = "",
    val url: String = "",
    val imageUrl: String? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null,
    val servings: String? = null,
    val ingredients: List<String> = emptyList(),
    val instructions: List<String> = emptyList(),
    val nutrition: Map<String, String> = emptyMap(),
    val categories: List<String> = emptyList(),
    val cuisine: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
)
