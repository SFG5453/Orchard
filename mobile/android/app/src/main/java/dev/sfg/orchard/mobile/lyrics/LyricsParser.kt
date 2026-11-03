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

import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.LyricWord
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

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
                val agent = entry.optString("agent").ifBlank { entry.optString("singer") }
                add(LyricLine(text, start.takeIf { hasTiming }, end, words, adlibs) to agent)
            }
        }.sortedBy { it.first.startMs ?: Long.MAX_VALUE }
        return withInferredEnds(withAgentLanes(lines))
    }

    fun ttml(value: String): List<LyricLine> {
        if (value.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's parser rejects some hardening features; apply each one independently.
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
                val agent = paragraph.resolveAgent()
                val key = paragraph.lineKey()
                add(LyricLine(text, start, end, words, adlibs, key = key) to agent)
            }
        }.sortedBy { it.first.startMs ?: Long.MAX_VALUE }
        return withInferredEnds(withAgentLanes(lines))
    }

    private fun withAgentLanes(rawLines: List<Pair<LyricLine, String>>): List<LyricLine> {
        val agentCounts = rawLines
            .map { it.second }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()

        val primaryAgent = agentCounts.maxByOrNull { it.value }?.key.orEmpty()
        val hasMultipleAgents = primaryAgent.isNotBlank() && agentCounts.size > 1

        return rawLines.map { (line, agent) ->
            val agentLane = if (hasMultipleAgents && agent.isNotBlank()) {
                if (agent != primaryAgent) "alternate" else "primary"
            } else {
                null
            }
            line.copy(agentLane = agentLane)
        }
    }

    private fun Element.lineKey(): String? {
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if ((attribute.localName ?: attribute.nodeName.substringAfter(':')) == "key") {
                return attribute.nodeValue.takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private fun Element.resolveAgent(): String {
        var current: Node? = this
        while (current is Element) {
            val agent = current.getAttribute("ttm:agent").takeIf(String::isNotBlank)
                ?: current.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "agent").takeIf(String::isNotBlank)
                ?: current.getAttribute("agent").takeIf(String::isNotBlank)
            if (!agent.isNullOrBlank()) return agent
            current = current.parentNode
        }
        return ""
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
