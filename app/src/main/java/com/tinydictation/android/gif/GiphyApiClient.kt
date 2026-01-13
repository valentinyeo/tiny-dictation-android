package com.tinydictation.android.gif

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Lightweight Giphy API client using OkHttp (no SDK).
 * Uses the Giphy REST API directly for searching and fetching trending GIFs.
 */
class GiphyApiClient(private val apiKey: String) {

    companion object {
        private const val TAG = "TinyDictation"
        private const val BASE_URL = "https://api.giphy.com/v1/gifs"
        private const val DEFAULT_LIMIT = 25
    }

    private val client = OkHttpClient()
    private val gson = Gson()

    /**
     * Search for GIFs matching the query.
     */
    suspend fun searchGifs(query: String, limit: Int = DEFAULT_LIMIT): Result<List<GifItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/search?api_key=$apiKey&q=$query&limit=$limit&rating=g"
                val response = executeRequest(url)
                Result.success(response.data.map { it.toGifItem() })
            } catch (e: Exception) {
                Log.e(TAG, "GiphyApiClient: Search failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get trending GIFs.
     */
    suspend fun getTrending(limit: Int = DEFAULT_LIMIT): Result<List<GifItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/trending?api_key=$apiKey&limit=$limit&rating=g"
                val response = executeRequest(url)
                Result.success(response.data.map { it.toGifItem() })
            } catch (e: Exception) {
                Log.e(TAG, "GiphyApiClient: Trending failed", e)
                Result.failure(e)
            }
        }
    }

    private fun executeRequest(url: String): GiphyResponse {
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Giphy API error: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            return gson.fromJson(body, GiphyResponse::class.java)
        }
    }
}

/**
 * Simple GIF item with the essential data for display and insertion.
 */
data class GifItem(
    val id: String,
    val title: String,
    val previewUrl: String,      // Small preview for grid display
    val previewWidth: Int,
    val previewHeight: Int,
    val originalUrl: String,     // Full size GIF URL
    val originalWidth: Int,
    val originalHeight: Int
)

// Giphy API response models
data class GiphyResponse(
    val data: List<GiphyGif>
)

data class GiphyGif(
    val id: String,
    val title: String,
    val images: GiphyImages
) {
    fun toGifItem(): GifItem {
        val preview = images.fixedWidthSmall ?: images.fixedWidth ?: images.original
        val original = images.original
        return GifItem(
            id = id,
            title = title,
            previewUrl = preview.url,
            previewWidth = preview.width.toIntOrNull() ?: 100,
            previewHeight = preview.height.toIntOrNull() ?: 100,
            originalUrl = original.url,
            originalWidth = original.width.toIntOrNull() ?: 480,
            originalHeight = original.height.toIntOrNull() ?: 480
        )
    }
}

data class GiphyImages(
    val original: GiphyImage,
    @SerializedName("fixed_width")
    val fixedWidth: GiphyImage?,
    @SerializedName("fixed_width_small")
    val fixedWidthSmall: GiphyImage?
)

data class GiphyImage(
    val url: String,
    val width: String,
    val height: String
)
