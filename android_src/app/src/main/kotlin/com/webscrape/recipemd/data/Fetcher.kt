package com.webscrape.recipemd.data

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Fetcher {

    private const val TAG = "Fetcher"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            chain.proceed(request)
        }
        .build()

    suspend fun fetchPage(url: String, retries: Int = 3): String? {
        for (attempt in 0 until retries) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    return response.body?.string()
                }
                Log.w(TAG, "HTTP ${response.code} for $url")
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1}/$retries failed for $url: ${e.message}")
            }
            if (attempt < retries - 1) {
                val wait = (1L shl attempt) * 1000L
                delay(wait)
            }
        }
        Log.e(TAG, "Failed to fetch $url after $retries attempts")
        return null
    }
}
