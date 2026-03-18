package com.webscrape.recipemd.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webscrape.recipemd.data.Fetcher
import com.webscrape.recipemd.data.MarkdownWriter
import com.webscrape.recipemd.data.RecipeParser
import com.webscrape.recipemd.data.SitemapCrawler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ScraperState(
    val isRunning: Boolean = false,
    val statusLog: List<String> = emptyList(),
    val savedFiles: List<String> = emptyList(),
    val succeeded: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    val urlInput: String = "",
)

class ScraperViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ScraperState())
    val state: StateFlow<ScraperState> = _state.asStateFlow()

    fun updateUrlInput(url: String) {
        _state.value = _state.value.copy(urlInput = url)
    }

    fun scrapeSingleUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        scrapeUrls(listOf(trimmed))
    }

    fun scrapeFromSitemap(limit: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = ScraperState(isRunning = true)
            log("Discovering recipe URLs from sitemap...")

            val urls = SitemapCrawler.discoverRecipeUrls { progress ->
                log(progress)
            }

            if (urls.isEmpty()) {
                log("No recipe URLs found.")
                _state.value = _state.value.copy(isRunning = false)
                return@launch
            }

            val toScrape = if (limit > 0) urls.take(limit) else urls
            log("Found ${urls.size} URLs, scraping ${toScrape.size}...")
            scrapeUrlList(toScrape)
        }
    }

    fun scrapeUrls(urls: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = ScraperState(isRunning = true)
            scrapeUrlList(urls)
        }
    }

    private suspend fun scrapeUrlList(urls: List<String>) {
        _state.value = _state.value.copy(total = urls.size)
        var succeeded = 0
        var failed = 0

        for ((i, url) in urls.withIndex()) {
            log("[${i + 1}/${urls.size}] $url")

            val html = Fetcher.fetchPage(url)
            if (html == null) {
                log("  FAILED to fetch")
                failed++
                updateCounts(succeeded, failed)
                continue
            }

            val recipe = RecipeParser.parse(html, url)
            if (recipe == null) {
                log("  FAILED to parse recipe")
                failed++
                updateCounts(succeeded, failed)
                continue
            }

            val file = MarkdownWriter.saveRecipe(getApplication(), recipe)
            if (file != null) {
                log("  Saved: ${file.name}")
                succeeded++
                _state.value = _state.value.copy(
                    savedFiles = _state.value.savedFiles + file.name
                )
            } else {
                log("  FAILED to save")
                failed++
            }
            updateCounts(succeeded, failed)

            // Rate limit
            if (i < urls.size - 1) delay(1000)
        }

        log("Done! $succeeded succeeded, $failed failed.")
        _state.value = _state.value.copy(isRunning = false)
    }

    private fun log(message: String) {
        _state.value = _state.value.copy(
            statusLog = _state.value.statusLog + message
        )
    }

    private fun updateCounts(succeeded: Int, failed: Int) {
        _state.value = _state.value.copy(succeeded = succeeded, failed = failed)
    }

    fun getOutputDir(): File {
        return File(getApplication<Application>().getExternalFilesDir(null), "recipes")
    }
}
