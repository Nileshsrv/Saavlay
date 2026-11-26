package com.saavn.lyricsoverlay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

sealed class LyricsLookup {
    data class Found(val lines: List<LyricLine>, val plain: String?, val instrumental: Boolean) : LyricsLookup()
    data object NotFound : LyricsLookup()
}

/**
 * Queries https://lrclib.net/api/search using the same 3-step fallback and 6h miss cache
 * as background.js in the web extension.
 */
object LrcLibClient {
    private const val API = "https://lrclib.net/api/search"
    private const val MISS_TTL_MS = 6L * 60L * 60L * 1000L

    private data class CacheEntry(val result: LyricsLookup, val ts: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun fetchLyrics(title: String, artist: String, durationSec: Double): LyricsLookup {
        val cleanedTitle = LyricsLib.cleanTitle(title)
        val primaryArtist = LyricsLib.splitArtists(artist).firstOrNull().orEmpty()
        val key = "${LyricsLib.norm(cleanedTitle)}|${LyricsLib.norm(primaryArtist)}"

        cache[key]?.let { entry ->
            if (entry.result is LyricsLookup.Found || System.currentTimeMillis() - entry.ts < MISS_TTL_MS) {
                return entry.result
            }
        }

        val lookup = withContext(Dispatchers.IO) {
            val attempts = buildList {
                if (primaryArtist.isNotEmpty()) {
                    add(mapOf("track_name" to cleanedTitle, "artist_name" to primaryArtist))
                }
                val q = "$cleanedTitle $primaryArtist".trim()
                if (q.isNotEmpty()) add(mapOf("q" to q))
                if (cleanedTitle.isNotEmpty()) add(mapOf("track_name" to cleanedTitle))
            }

            for (params in attempts) {
                val results = runCatching { queryApi(params) }.getOrDefault(emptyList())
                val best = LyricsLib.pickBest(results, title, artist, durationSec)
                if (best != null) {
                    val parsed = LyricsLib.parseLrc(best.syncedLyrics)
                    return@withContext LyricsLookup.Found(parsed, best.plainLyrics, best.instrumental)
                }
            }
            LyricsLookup.NotFound
        }

        cache[key] = CacheEntry(lookup, System.currentTimeMillis())
        return lookup
    }

    private fun queryApi(params: Map<String, String>): List<LrcResult> {
        val qs = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = (URL("$API?$qs").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SaavnLyricsOverlay/1.0")
        }
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                LrcResult(
                    trackName = obj.optString("trackName", obj.optString("name", "")),
                    artistName = obj.optString("artistName", ""),
                    durationSec = obj.optDouble("duration", 0.0),
                    syncedLyrics = obj.optString("syncedLyrics", "").ifBlank { null },
                    plainLyrics = obj.optString("plainLyrics", "").ifBlank { null },
                    instrumental = obj.optBoolean("instrumental", false)
                )
            }
        } finally {
            conn.disconnect()
        }
    }
}
