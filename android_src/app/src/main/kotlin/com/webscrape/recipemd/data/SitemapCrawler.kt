package com.webscrape.recipemd.data

import android.util.Log
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object SitemapCrawler {

    private const val TAG = "SitemapCrawler"
    private const val BASE_URL = "https://www.indianhealthyrecipes.com"

    suspend fun discoverRecipeUrls(
        baseUrl: String = BASE_URL,
        onProgress: (String) -> Unit = {},
    ): List<String> {
        val cleanBase = baseUrl.trimEnd('/')

        // Strategy 1: Try XML sitemaps
        val sitemapUrls = listOf(
            "$cleanBase/sitemap_index.xml",
            "$cleanBase/sitemap.xml",
            "$cleanBase/post-sitemap.xml",
        )

        for (sitemapUrl in sitemapUrls) {
            onProgress("Trying sitemap: $sitemapUrl")
            val content = Fetcher.fetchPage(sitemapUrl, retries = 2) ?: continue

            // Try XML
            val xmlUrls = parseSitemapXml(content, cleanBase, onProgress)
            if (xmlUrls != null && xmlUrls.isNotEmpty()) {
                val recipeUrls = xmlUrls.filter { isRecipeUrl(it, cleanBase) }
                if (recipeUrls.isNotEmpty()) {
                    Log.i(TAG, "Found ${recipeUrls.size} recipe URLs from XML sitemap")
                    onProgress("Found ${recipeUrls.size} recipes from sitemap")
                    return recipeUrls
                }
            }

            // Try as HTML sitemap
            val htmlUrls = parseSitemapHtml(content, cleanBase)
            if (htmlUrls.isNotEmpty()) {
                Log.i(TAG, "Found ${htmlUrls.size} recipe URLs from HTML sitemap")
                onProgress("Found ${htmlUrls.size} recipes from HTML sitemap")
                return htmlUrls
            }
        }

        // Strategy 2: Crawl recipe index
        onProgress("Crawling recipe index...")
        val indexUrls = crawlRecipeIndex(cleanBase, onProgress)
        if (indexUrls.isNotEmpty()) {
            Log.i(TAG, "Found ${indexUrls.size} recipe URLs from index crawl")
            onProgress("Found ${indexUrls.size} recipes from index")
            return indexUrls
        }

        onProgress("Could not discover any recipe URLs")
        return emptyList()
    }

    private suspend fun parseSitemapXml(
        content: String,
        baseUrl: String,
        onProgress: (String) -> Unit,
    ): List<String>? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(content))

            val urls = mutableListOf<String>()
            val childSitemaps = mutableListOf<String>()
            var inLoc = false
            var inSitemap = false

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "sitemap" -> inSitemap = true
                            "loc" -> inLoc = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inLoc) {
                            val url = parser.text.trim()
                            if (inSitemap && "post-sitemap" in url) {
                                childSitemaps.add(url)
                            } else if (!inSitemap) {
                                urls.add(url)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "sitemap" -> inSitemap = false
                            "loc" -> inLoc = false
                        }
                    }
                }
            }

            // Fetch child sitemaps
            if (childSitemaps.isNotEmpty()) {
                for (childUrl in childSitemaps) {
                    onProgress("Fetching child sitemap: $childUrl")
                    val childContent = Fetcher.fetchPage(childUrl, retries = 2) ?: continue
                    val childUrls = parseSitemapXml(childContent, baseUrl, onProgress)
                    if (childUrls != null) urls.addAll(childUrls)
                }
            }

            urls
        } catch (e: Exception) {
            Log.d(TAG, "XML parse failed: ${e.message}")
            null
        }
    }

    private fun parseSitemapHtml(content: String, baseUrl: String): List<String> {
        val doc = Jsoup.parse(content)
        return doc.select("a[href]")
            .map { it.attr("href") }
            .filter { it.startsWith(baseUrl) && isRecipeUrl(it, baseUrl) }
            .distinct()
    }

    private suspend fun crawlRecipeIndex(
        baseUrl: String,
        onProgress: (String) -> Unit,
    ): List<String> {
        val indexUrl = "$baseUrl/recipes/"
        onProgress("Fetching recipe index: $indexUrl")
        val content = Fetcher.fetchPage(indexUrl, retries = 2) ?: return emptyList()

        val doc = Jsoup.parse(content)
        val recipeUrls = mutableSetOf<String>()
        val categoryUrls = mutableSetOf<String>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trimEnd('/') + "/"
            if (!href.startsWith(baseUrl)) continue
            if (isRecipeUrl(href, baseUrl)) {
                recipeUrls.add(href)
            } else if ("/recipes/" in href && href != indexUrl) {
                categoryUrls.add(href)
            }
        }

        onProgress("Found ${recipeUrls.size} recipes, ${categoryUrls.size} categories from index")

        for ((i, catUrl) in categoryUrls.sorted().withIndex()) {
            onProgress("Crawling category [${i + 1}/${categoryUrls.size}]: $catUrl")
            delay(1000)
            val catContent = Fetcher.fetchPage(catUrl, retries = 2) ?: continue
            val catDoc = Jsoup.parse(catContent)
            for (a in catDoc.select("a[href]")) {
                val href = a.attr("href").trimEnd('/') + "/"
                if (href.startsWith(baseUrl) && isRecipeUrl(href, baseUrl)) {
                    recipeUrls.add(href)
                }
            }
        }

        return recipeUrls.toList()
    }

    private fun isRecipeUrl(url: String, baseUrl: String): Boolean {
        val path = url.replace(baseUrl, "").trim('/')
        if (path.isEmpty()) return false

        val skipWords = listOf(
            "category", "tag", "page", "author", "wp-content",
            "recipes", "about", "contact", "privacy", "disclaimer",
            "sitemap", "feed",
        )
        val skipExtensions = listOf(".xml", ".jpg", ".png", ".gif", ".css", ".js")
        val lower = path.lowercase()

        if (lower in skipWords) return false
        if (skipWords.any { lower.startsWith("$it/") }) return false
        if (skipExtensions.any { lower.endsWith(it) }) return false
        if ("/" in path) return false

        return true
    }
}
