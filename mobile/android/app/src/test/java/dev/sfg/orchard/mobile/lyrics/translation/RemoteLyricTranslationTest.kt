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

package dev.sfg.orchard.mobile.lyrics.translation

import com.sun.net.httpserver.HttpServer
import dev.sfg.orchard.mobile.model.LyricTranslationProvider
import dev.sfg.orchard.mobile.model.LyricTranslationLanguage
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class RemoteLyricTranslationTest {
    @Test fun responseKeepsEveryLineInPlace() {
        val openai = """{"choices":[{"message":{"content":"[\"I love you\",\"Good night\"]"}}]}"""
        assertEquals(listOf("I love you", "Good night"),
            parseRemoteLines(openai, LyricTranslationProvider.OPENAI, 2))
        assertNull(parseRemoteLines(openai, LyricTranslationProvider.OPENAI, 3))
        val claude = """{"content":[{"type":"text","text":"```json\n[\"Hello\",\"World\"]\n```"}]}"""
        assertEquals(listOf("Hello", "World"), parseRemoteLines(claude, LyricTranslationProvider.CLAUDE, 2))
        assertNull(parseRemoteLines("""{"choices":[{"message":{"content":"[\"\",42]"}}]}""",
            LyricTranslationProvider.GEMINI, 2))
    }

    @Test fun accountResponseKeepsSongLineAlignment() {
        val body = """{"song_id":"one","lines":["Hello","World"],"cached":true}"""
        assertEquals(listOf("Hello", "World"), parseAccountLines(body, 2))
        assertNull(parseAccountLines(body, 3))
        assertNull(parseAccountLines("""{"lines":["Hello",42]}""", 2))
        assertNull(parseAccountLines("""{"lines":[""]}""", 1))
    }

    @Test fun accountQuotaUsesWorkerValuesAndRejectsMissingOrExpiredAllowance() {
        val body = JSONObject("""{"quota":{"daily_limit":50,"remaining":37,"resets_at":2000}}""")
        val quota = requireNotNull(parseAccountQuota(body, now = 1000))
        assertEquals(50L, quota.dailyLimit)
        assertEquals(37L, quota.remaining)
        assertTrue(quota.description(now = 1000).contains("37 translations left"))
        assertTrue(quota.description(now = 2000).contains("unavailable"))
        assertNull(parseAccountQuota(body, now = 2000))
        assertNull(parseAccountQuota(JSONObject(), now = 1000))
        assertNull(parseAccountQuota(JSONObject("""{"quota":{"daily_limit":50,"resets_at":2000}}"""), now = 1000))
        for (remaining in listOf(-1, 51, 0.5, "37")) {
            body.getJSONObject("quota").put("remaining", remaining)
            assertNull(parseAccountQuota(body, now = 1000))
        }
        body.getJSONObject("quota").put("remaining", 0)
        assertEquals(0L, requireNotNull(parseAccountQuota(body, now = 1000)).remaining)
    }

    @Test fun endpointsAndCacheAreScoped() {
        assertEquals("https://api.anthropic.com/v1/messages",
            RemoteLyricTranslation.endpoint(LyricTranslationProvider.CLAUDE, "", "key").toString())
        assertEquals("https://example.com/v1/chat/completions",
            RemoteLyricTranslation.endpoint(LyricTranslationProvider.CUSTOM, "https://example.com/v1/", "key").toString())
        assertNotEquals(
            RemoteLyricTranslation.cacheRevision(LyricTranslationProvider.CUSTOM, "one", "https://example.com/v1"),
            RemoteLyricTranslation.cacheRevision(LyricTranslationProvider.CUSTOM, "two", "https://example.com/v1"),
        )
    }

    @Test fun targetLanguagesHaveSeparateCaches() {
        val revisions = LyricTranslationLanguage.entries.map {
            RemoteLyricTranslation.cacheRevision(LyricTranslationProvider.CUSTOM, "model", "https://example.com/v1", it)
        }
        assertEquals(6, revisions.toSet().size)
    }

    @Test fun customApiReceivesOrderedLines() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request: JSONObject? = null
        server.createContext("/v1/chat/completions") { exchange ->
            request = JSONObject(exchange.requestBody.bufferedReader().readText())
            val answer = """{"choices":[{"message":{"content":"[\"Hello\",\"Good night\"]"}}]}"""
            exchange.sendResponseHeaders(200, answer.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(answer.toByteArray()) }
        }
        server.start()
        try {
            val output = RemoteLyricTranslation(okhttp3.OkHttpClient()).translate(
                LyricTranslationProvider.CUSTOM, "test-model", "http://127.0.0.1:${server.address.port}/v1", "",
                listOf("Hola", "Buenas noches"), LyricTranslationLanguage.FRENCH,
            )
            assertEquals(listOf("Hello", "Good night"), output)
            val sent = requireNotNull(request)
            assertEquals("test-model", sent.getString("model"))
            val instruction = sent.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(instruction.contains("natural French"))
            assertTrue(instruction.contains("Keep French lines unchanged"))
            assertTrue(sent.getJSONArray("messages").getJSONObject(1).getString("content").contains("Buenas noches"))
        } finally {
            server.stop(0)
        }
    }
}
