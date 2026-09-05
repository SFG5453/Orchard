/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.lyrics

import dev.sfg.orchard.mobile.catalog.InnerTubeClient
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.LyricWord
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Native port of Orchard desktop's am-lyrics → LRCLIB → YouTube resolver chain. */
class LyricsRepository(
    http: OkHttpClient,
    private val innerTube: InnerTubeClient,
) {
    private data class Metadata(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val videoId: String,
    )

    private val providerHttp = http.newBuilder().callTimeout(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val cache = object : LinkedHashMap<String, List<LyricLine>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>?): Boolean = size > 32
    }

    suspend fun lyrics(track: Track): List<LyricLine> = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[track.id] }?.let { return@withContext it }
        val metadata = track.lyricsMetadata() ?: return@withContext emptyList()

        // Match orchardv2: prefer am-lyrics when it is synchronized, let LRCLIB
        // replace an unsynchronized am-lyrics result, then ask YouTube last.
        val amLyrics = runCatching { fromAmLyrics(metadata) }.getOrDefault(emptyList())
        val result = if (amLyrics.isSynchronized()) {
            amLyrics
        } else {
            val lrcLib = runCatching { fromLrcLib(metadata) }.getOrDefault(emptyList())
            when {
                lrcLib.isSynchronized() -> lrcLib
                amLyrics.isNotEmpty() -> amLyrics
                lrcLib.isNotEmpty() -> lrcLib
                else -> runCatching { LyricsParser.plain(innerTube.lyrics(metadata.videoId)) }.getOrDefault(emptyList())
            }
        }

        synchronized(cache) { cache[track.id] = result }
        result
    }

    private fun fromAmLyrics(metadata: Metadata): List<LyricLine> {
        runCatching { fromBiniLyrics(metadata) }.getOrDefault(emptyList())
            .takeIf(List<LyricLine>::isNotEmpty)?.let { return it }
        runCatching { fromBetterLyrics(metadata) }.getOrDefault(emptyList())
            .takeIf(List<LyricLine>::isNotEmpty)?.let { return it }
        return fromLyricsPlus(metadata)
    }

    private fun fromBiniLyrics(metadata: Metadata): List<LyricLine> {
        val url = "https://lyrics-api.binimum.org/".toHttpUrl().newBuilder()
            .addQueryParameter("track", metadata.title)
            .addQueryParameter("artist", metadata.artist)
            .withAlbumAndDuration(metadata, "album", "duration")
            .build()
        val root = requestObject(url) ?: return emptyList()
        val results = root.optJSONArray("results") ?: return emptyList()
        val lyricsUrl = (0 until results.length()).firstNotNullOfOrNull { index ->
            results.optJSONObject(index)?.optString("lyricsUrl")?.takeIf(String::isNotBlank)
        } ?: return emptyList()
        return requestText(lyricsUrl.toHttpUrl())?.let(LyricsParser::ttml).orEmpty()
    }

    private fun fromBetterLyrics(metadata: Metadata): List<LyricLine> {
        val url = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("s", metadata.title)
            .addQueryParameter("a", metadata.artist)
            .withAlbumAndDuration(metadata, "al", "d")
            .addQueryParameter("videoId", metadata.videoId)
            .build()
        val root = requestObject(url) ?: return emptyList()
        val ttml = root.optString("ttml").ifBlank { root.optString("lyrics") }
        return LyricsParser.ttml(ttml)
    }

    private fun fromLyricsPlus(metadata: Metadata): List<LyricLine> {
        for (server in LYRICS_PLUS_SERVERS) {
            val url = "$server/v2/lyrics/get".toHttpUrl().newBuilder()
                .addQueryParameter("title", metadata.title)
                .addQueryParameter("artist", metadata.artist)
                .withAlbumAndDuration(metadata, "album", "duration")
                .build()
            val lines = runCatching { requestObject(url)?.let(LyricsParser::amPayload).orEmpty() }
                .getOrDefault(emptyList())
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    private fun fromLrcLib(metadata: Metadata): List<LyricLine> {
        val exactUrl = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", metadata.title)
            .addQueryParameter("artist_name", metadata.artist)
            .withAlbumAndDuration(metadata, "album_name", "duration")
            .build()
        val exact = runCatching { requestObject(exactUrl)?.let(LyricsParser::lrcLib).orEmpty() }
            .getOrDefault(emptyList())
        if (exact.isNotEmpty()) return exact

        val searchUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", metadata.title)
            .addQueryParameter("artist_name", metadata.artist)
            .build()
        val search = runCatching { requestArray(searchUrl) }.getOrNull() ?: return emptyList()
        for (index in 0 until search.length()) {
            val lines = search.optJSONObject(index)?.let(LyricsParser::lrcLib).orEmpty()
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    private fun HttpUrl.Builder.withAlbumAndDuration(
        metadata: Metadata,
        albumKey: String,
        durationKey: String,
    ): HttpUrl.Builder = apply {
        if (metadata.album.isNotBlank()) addQueryParameter(albumKey, metadata.album)
        if (metadata.durationMs > 0) addQueryParameter(durationKey, (metadata.durationMs / 1_000).toString())
    }

    private fun requestObject(url: HttpUrl): JSONObject? = requestText(url)?.let(::JSONObject)
    private fun requestArray(url: HttpUrl): JSONArray? = requestText(url)?.let(::JSONArray)

    private fun requestText(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        providerHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string()
        }
    }

    private fun Track.lyricsMetadata(): Metadata? {
        val cleanTitle = title.cleanLookupText()
        val cleanArtist = artist.cleanLookupText()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null
        return Metadata(
            cleanTitle,
            cleanArtist,
            album.cleanLookupText().takeUnless { it.isEngagementMetric() }.orEmpty(),
            durationMs,
            id,
        )
    }

    private fun String.cleanLookupText(): String = replace(
        Regex("\\([^)]*(?:official|video|visualizer|lyrics?|audio|remaster|hd|4k)[^)]*\\)", RegexOption.IGNORE_CASE),
        "",
    ).replace(
        Regex("\\[[^]]*(?:official|video|visualizer|lyrics?|audio|remaster|hd|4k)[^]]*]", RegexOption.IGNORE_CASE),
        "",
    ).replace(Regex("\\s+-\\s+Topic$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun String.isEngagementMetric(): Boolean = contains(
        Regex("\\b(?:plays?|views?|listeners?|subscribers?)\\b", RegexOption.IGNORE_CASE),
    )

    private fun List<LyricLine>.isSynchronized(): Boolean = any { it.startMs != null }

    private companion object {
        const val PROVIDER_TIMEOUT_SECONDS = 8L
        const val USER_AGENT = "Orchard Android/2.0"
        val LYRICS_PLUS_SERVERS = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus-seven.vercel.app",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyrics-plus-backend.vercel.app",
        )
    }
}

internal object LyricsParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2}(?:\\.\\d{1,3})?)]")

    fun lrcLib(root: JSONObject): List<LyricLine> {
        if (root.optBoolean("instrumental")) return listOf(LyricLine("Instrumental"))
        val synced = root.optString("syncedLyrics").takeIf(String::isNotBlank)?.let(::lrc)
        return synced?.takeIf(List<LyricLine>::isNotEmpty)
            ?: root.optString("plainLyrics").takeIf(String::isNotBlank)?.let(::plain).orEmpty()
    }

    fun lrc(value: String): List<LyricLine> {
        val parsed = value.lineSequence().mapNotNull { raw ->
            val match = timestamp.find(raw) ?: return@mapNotNull null
            val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val seconds = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val startMs = minutes * 60_000 + (seconds * 1_000).toLong()
            LyricLine(raw.substring(match.range.last + 1).trim(), startMs)
        }.filter { it.text.isNotBlank() }.sortedBy { it.startMs }.toList()
        return withInferredEnds(parsed)
    }

    fun plain(value: String): List<LyricLine> =
        value.lineSequence().map(String::trim).filter(String::isNotBlank).map(::LyricLine).toList()

    fun amPayload(root: JSONObject): List<LyricLine> {
        val values = root.optJSONArray("lyrics")
            ?: root.optJSONObject("data")?.optJSONArray("lyrics")
            ?: root.optJSONArray("data")
            ?: return emptyList()
        val lines = buildList {
            for (index in 0 until values.length()) {
                val entry = values.optJSONObject(index) ?: continue
                val syllables = entry.optJSONArray("syllabus") ?: entry.optJSONArray("words")
                val text = entry.optString("text").ifBlank { syllables.joinText() }
                    .replace(Regex("\\s+"), " ").trim()
                if (text.isBlank()) continue
                val hasTiming = entry.has("time")
                val start = timeMs(entry.opt("time"))
                val duration = timeMs(entry.opt("duration"))
                val explicitEnd = timeMs(entry.opt("endTime"))
                val end = explicitEnd.takeIf { it > start } ?: (start + duration).takeIf { duration > 0 }
                val (words, adlibs) = timedWords(syllables, start, end, text)
                add(LyricLine(text, start.takeIf { hasTiming }, end, words, adlibs))
            }
        }.sortedBy { it.startMs ?: Long.MAX_VALUE }
        return withInferredEnds(lines)
    }

    fun ttml(value: String): List<LyricLine> {
        if (value.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's parser rejects some of these hardening features outright, and an
            // unsupported one used to throw and kill every TTML parse. Apply what sticks.
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl",
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
            ).forEach { feature ->
                runCatching { setFeature(feature, feature.endsWith("disallow-doctype-decl")) }
            }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(value)))
        val paragraphs = document.getElementsByTagNameNS("*", "p")
        val lines = buildList {
            for (index in 0 until paragraphs.length) {
                val paragraph = paragraphs.item(index) as? Element ?: continue
                val text = paragraph.textContent.orEmpty().replace(Regex("\\s+"), " ").trim()
                if (text.isBlank()) continue
                val spans = paragraph.getElementsByTagNameNS("*", "span")
                val (words, adlibs) = timedTtmlWords(paragraph, spans, text)
                val start = paragraph.getAttribute("begin").takeIf(String::isNotBlank)?.let(::timeMs)
                    ?: words.firstOrNull()?.startMs ?: adlibs.firstOrNull()?.startMs
                val end = paragraph.getAttribute("end").takeIf(String::isNotBlank)?.let(::timeMs)
                    ?: (words + adlibs).mapNotNull(LyricWord::endMs).maxOrNull()
                add(LyricLine(text, start, end, words, adlibs))
            }
        }.sortedBy { it.startMs ?: Long.MAX_VALUE }
        return withInferredEnds(lines)
    }

    private fun withInferredEnds(lines: List<LyricLine>): List<LyricLine> = lines.mapIndexed { index, line ->
        val lineEnd = line.endMs ?: lines.getOrNull(index + 1)?.startMs
        line.copy(
            endMs = lineEnd,
            words = inferWordEnds(line.words, lineEnd),
            adlibs = inferWordEnds(line.adlibs, lineEnd),
        )
    }

    private fun timedWords(
        syllables: JSONArray?,
        lineStartMs: Long,
        lineEndMs: Long?,
        lineText: String,
    ): Pair<List<LyricWord>, List<LyricWord>> {
        if (syllables == null) return emptyList<LyricWord>() to emptyList()
        val words = mutableListOf<LyricWord>()
        val adlibs = mutableListOf<LyricWord>()
        for (index in 0 until syllables.length()) {
            val syllable = syllables.optJSONObject(index) ?: continue
            val text = syllable.optString("text")
            if (text.isBlank()) continue
            val start = if (syllable.has("time")) timeMs(syllable.opt("time")) else lineStartMs
            val duration = timeMs(syllable.opt("duration"))
            val end = (start + duration).takeIf { duration > 0 }
                ?: lineEndMs.takeIf { syllables.length() == 1 && it != null && it > start }
            val word = LyricWord(text, start, end)
            if (syllable.optBoolean("isBackground")) adlibs += word else words += word
        }
        return mergeTimedWords(words, lineText) to mergeTimedWords(adlibs)
    }

    private fun timedTtmlWords(
        paragraph: Element,
        spans: NodeList,
        lineText: String,
    ): Pair<List<LyricWord>, List<LyricWord>> {
        val words = mutableListOf<LyricWord>()
        val adlibs = mutableListOf<LyricWord>()
        for (index in 0 until spans.length) {
            val span = spans.item(index) as? Element ?: continue
            val begin = span.getAttribute("begin").takeIf(String::isNotBlank) ?: continue
            if (span.getElementsByTagNameNS("*", "span").hasTimedElement()) continue
            val text = span.textContent.orEmpty()
            if (text.isBlank()) continue
            val word = LyricWord(
                text = text,
                startMs = timeMs(begin),
                endMs = span.getAttribute("end").takeIf(String::isNotBlank)?.let(::timeMs),
            )
            if (span.isBackgroundSpan(paragraph)) adlibs += word else words += word
        }
        return mergeTimedWords(words, lineText) to mergeTimedWords(adlibs)
    }

    private fun mergeTimedWords(input: List<LyricWord>, lineText: String = ""): List<LyricWord> {
        val clean = input.filter { it.text.isNotBlank() }
        val canonicalLine = lineText.replace(Regex("\\s+"), " ").trim()
        if (canonicalLine.isBlank() && clean.none { it.text.any(Char::isWhitespace) }) {
            return clean.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotBlank() }
        }
        val output = mutableListOf<LyricWord>()
        var currentText = ""
        var currentStart = 0L
        var currentEnd: Long? = null
        var lineCursor = 0
        fun finish() {
            if (currentText.isNotBlank()) output += LyricWord(currentText, currentStart, currentEnd)
            currentText = ""
            currentEnd = null
        }
        clean.forEach { word ->
            Regex("\\s*\\S+\\s*").findAll(word.text).forEach { match ->
                val syllable = match.value.trim()
                val syllableIndex = canonicalLine.indexOf(syllable, lineCursor)
                val canonicalWordBreak = syllableIndex >= lineCursor &&
                    canonicalLine.substring(lineCursor, syllableIndex).any(Char::isWhitespace)
                val tokenWordBreak = syllableIndex < 0 && match.value.firstOrNull()?.isWhitespace() == true
                if (canonicalWordBreak || tokenWordBreak) finish()
                if (currentText.isBlank()) currentStart = word.startMs
                currentText += syllable
                if (word.endMs != null && (currentEnd == null || word.endMs > currentEnd!!)) currentEnd = word.endMs
                if (syllableIndex >= 0) lineCursor = syllableIndex + syllable.length
                if (match.value.lastOrNull()?.isWhitespace() == true) finish()
            }
        }
        finish()
        return output.ifEmpty { clean }
    }

    private fun inferWordEnds(words: List<LyricWord>, lineEndMs: Long?): List<LyricWord> =
        words.mapIndexed { index, word ->
            word.copy(endMs = word.endMs ?: words.getOrNull(index + 1)?.startMs ?: lineEndMs)
        }

    private fun timeMs(value: Any?): Long = when (value) {
        null, JSONObject.NULL -> 0
        is Byte, is Short, is Int, is Long -> (value as Number).toLong().coerceAtLeast(0)
        is Number -> if (value.toDouble() % 1.0 == 0.0) value.toLong().coerceAtLeast(0)
            else (value.toDouble() * 1_000).toLong().coerceAtLeast(0)
        else -> timeMs(value.toString())
    }

    private fun timeMs(value: String): Long {
        val text = value.trim()
        if (text.isBlank()) return 0
        if (text.endsWith("ms", true)) return text.dropLast(2).toDoubleOrNull()?.toLong()?.coerceAtLeast(0) ?: 0
        if (text.endsWith("s", true)) return ((text.dropLast(1).toDoubleOrNull() ?: 0.0) * 1_000).toLong().coerceAtLeast(0)
        val parts = text.split(':').mapNotNull(String::toDoubleOrNull)
        if (parts.size == 3) return ((parts[0] * 3_600 + parts[1] * 60 + parts[2]) * 1_000).toLong().coerceAtLeast(0)
        if (parts.size == 2) return ((parts[0] * 60 + parts[1]) * 1_000).toLong().coerceAtLeast(0)
        return ((text.toDoubleOrNull() ?: 0.0) * 1_000).toLong().coerceAtLeast(0)
    }

    private fun JSONArray?.joinText(): String {
        if (this == null) return ""
        return buildString {
            for (index in 0 until length()) append(optJSONObject(index)?.optString("text").orEmpty())
        }
    }

    private fun NodeList.hasTimedElement(): Boolean = (0 until length).any { index ->
        (item(index) as? Element)?.getAttribute("begin")?.isNotBlank() == true
    }

    private fun Element.isBackgroundSpan(paragraph: Element): Boolean {
        var node: org.w3c.dom.Node? = this
        while (node is Element) {
            if (node.getAttribute("ttm:role") == "x-bg" || node.getAttribute("role") == "x-bg") return true
            if (node === paragraph) break
            node = node.parentNode
        }
        return false
    }
}
