/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.sfg.orchard.mobile.lyrics.translation

import com.sun.net.httpserver.HttpServer
import dev.sfg.orchard.mobile.model.LyricTranslationProvider
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
                listOf("Hola", "Buenas noches"),
            )
            assertEquals(listOf("Hello", "Good night"), output)
            val sent = requireNotNull(request)
            assertEquals("test-model", sent.getString("model"))
            assertTrue(sent.getJSONArray("messages").getJSONObject(1).getString("content").contains("Buenas noches"))
        } finally {
            server.stop(0)
        }
    }
}
