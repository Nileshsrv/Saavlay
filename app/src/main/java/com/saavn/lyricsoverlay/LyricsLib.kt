package com.saavn.lyricsoverlay

import android.graphics.Bitmap
import android.graphics.Color
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LyricLine(val timeSec: Double, val text: String)

data class LrcResult(
    val trackName: String,
    val artistName: String,
    val durationSec: Double,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val instrumental: Boolean
)

data class CoverTheme(
    val baseColor: Int,
    val glowColor: Int,
    val accentColor: Int
)

/**
 * Kotlin port of saavn-lyrics/lib.js:
 * LRC parsing, title/artist normalization, LRCLIB scoring, and cover-art palette math.
 */
object LyricsLib {

    private val DIACRITICS_REGEX = Regex("[\\u0300-\\u036f]")
    private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{M}\\p{N}\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val BRACKET_TAG_REGEX = Regex("\\s*[(\\[][^\\)\\]]*[\\)\\]]")
    private val BRACKET_NOISE_WORDS = Regex(
        "\\b(from|feat|ft|with|remix|lofi|lo-fi|version|reprise|unplugged|acoustic|original|motion picture|soundtrack|ost|theme)\\b",
        RegexOption.IGNORE_CASE
    )
    private val DASH_TAG_REGEX = Regex("\\s+-\\s+(from|feat|ft)\\b.*$", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT_REGEX = Regex(
        "\\s*(?:,|;|&|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*",
        RegexOption.IGNORE_CASE
    )
    private val LRC_STAMP_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        return decomposed
            .replace(DIACRITICS_REGEX, "")
            .replace(NON_ALNUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun cleanTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        return title
            .replace(BRACKET_TAG_REGEX) { match ->
                if (BRACKET_NOISE_WORDS.containsMatchIn(match.value)) "" else match.value
            }
            .replace(DASH_TAG_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun splitArtists(artist: String?): List<String> {
        if (artist.isNullOrBlank()) return emptyList()
        return artist.split(ARTIST_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun parseLrc(text: String?): List<LyricLine> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<LyricLine>()
        for (raw in text.split(Regex("\\r?\\n"))) {
            val stamps = LRC_STAMP_REGEX.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val words = raw.replace(LRC_STAMP_REGEX, "").trim()
            for (m in stamps) {
                val mins = m.groupValues[1].toDouble()
                val secs = m.groupValues[2].toDouble()
                val fracStr = m.groupValues[3]
                val frac = if (fracStr.isNotEmpty()) ("0.$fracStr").toDouble() else 0.0
                out.add(LyricLine(mins * 60.0 + secs + frac, words))
            }
        }
        return out.sortedBy { it.timeSec }
    }

    fun findLineIndex(lines: List<LyricLine>, tSec: Double): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeSec <= tSec) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun pickBest(results: List<LrcResult>, title: String, artist: String, durationSec: Double): LrcResult? {
        val wantTitle = norm(cleanTitle(title))
        val wantArtists = splitArtists(artist).map { norm(it) }.filter { it.isNotEmpty() }
        var best: LrcResult? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (r in results) {
            if (r.syncedLyrics.isNullOrBlank() && r.plainLyrics.isNullOrBlank() && !r.instrumental) continue
            val rt = norm(r.trackName)
            val ra = norm(r.artistName)
            if (rt.isEmpty() || wantTitle.isEmpty()) continue
            if (!(rt == wantTitle || rt.contains(wantTitle) || wantTitle.contains(rt))) continue

            val artistOk = ra.isNotEmpty() && wantArtists.any { a -> ra.contains(a) || a.contains(ra) }
            val diff = if (durationSec > 0 && r.durationSec > 0) abs(durationSec - r.durationSec) else null
            if (!artistOk && !(diff != null && diff <= 4.0)) continue

            val score =
                (if (!r.syncedLyrics.isNullOrBlank()) 4.0 else 0.0) +
                (if (artistOk) 3.0 else 0.0) +
                (if (rt == wantTitle) 2.0 else 0.0) -
                (if (diff == null) 0.0 else min(diff, 30.0) / 5.0)

            if (score > bestScore) {
                best = r
                bestScore = score
            }
        }
        return best
    }

    private data class HueBin(var w: Double = 0.0, var r: Double = 0.0, var g: Double = 0.0, var b: Double = 0.0)

    fun extractCoverTheme(bitmap: Bitmap?): CoverTheme {
        if (bitmap == null) {
            return CoverTheme(
                baseColor = Color.argb(235, 18, 22, 28),
                glowColor = Color.argb(120, 45, 85, 110),
                accentColor = Color.rgb(120, 210, 175)
            )
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val pixels = IntArray(48 * 48)
        scaled.getPixels(pixels, 0, 48, 0, 0, 48, 48)

        val bins = Array(12) { HueBin() }
        var tr = 0.0
        var tg = 0.0
        var tb = 0.0
        var n = 0
        val hsl = FloatArray(3)

        for (px in pixels) {
            val a = Color.alpha(px)
            if (a < 128) continue
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            tr += r; tg += g; tb += b; n++

            Color.RGBToHSV(r, g, b, hsl)
            val l = hsl[2] * (1f - hsl[1] / 2f)
            if (l < 0.08f || l > 0.95f) continue
            val w = (hsl[1] * (1f - abs(2f * l - 1f))).toDouble()
            val idx = (floor(hsl[0] / 30.0).toInt()) % 12
            bins[idx].w += w
            bins[idx].r += r * w
            bins[idx].g += g * w
            bins[idx].b += b * w
        }

        if (n == 0) {
            return CoverTheme(Color.argb(235, 22, 28, 30), Color.argb(120, 40, 60, 60), Color.rgb(110, 220, 170))
        }

        var top = 0
        for (i in 1 until 12) {
            if (bins[i].w > bins[top].w) top = i
        }

        val primaryColor = if (bins[top].w < n * 0.02) {
            Color.rgb((tr / n).roundToInt(), (tg / n).roundToInt(), (tb / n).roundToInt())
        } else {
            val b = bins[top]
            Color.rgb((b.r / b.w).roundToInt(), (b.g / b.w).roundToInt(), (b.b / b.w).roundToInt())
        }

        val hsv = FloatArray(3)
        Color.colorToHSV(primaryColor, hsv)

        val baseHsv = floatArrayOf(hsv[0], min(0.55f, hsv[1] * 0.7f), 0.16f)
        val glowHsv = floatArrayOf(hsv[0], max(0.35f, min(0.85f, hsv[1] * 1.1f)), 0.42f)
        val accentHsv = floatArrayOf(hsv[0], max(0.45f, min(0.90f, hsv[1] * 1.15f)), 0.88f)

        val base = Color.HSVToColor(238, baseHsv)
        val glow = Color.HSVToColor(140, glowHsv)
        val accent = Color.HSVToColor(255, accentHsv)
        return CoverTheme(base, glow, accent)
    }
}
