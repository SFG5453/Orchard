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

package dev.sfg.orchard.mobile.youtube

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import dev.sfg.orchard.mobile.provider.ProviderException
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YouTubeProviderTest {
    private val assets = File(checkNotNull(System.getProperty("orchard.providerAssets")))
    private val cache = kotlin.io.path.createTempDirectory("provider").toFile()
    private val provider = YouTubeProvider(OkHttpClient(), cache) { name -> File(assets, name).takeIf(File::isFile)?.readBytes() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/echo") { exchange ->
            val body = "${exchange.requestMethod} ${exchange.requestHeaders.getFirst("X-Test")} " +
                exchange.requestBody.readBytes().decodeToString() + " 🎵"
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.add("Set-Cookie", "a=1")
            exchange.responseHeaders.add("Set-Cookie", "b=2")
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @After fun stop() {
        server.stop(0)
        cache.deleteRecursively()
    }

    @Test fun `desktop bytecode answers through the shared host`() = runBlocking {
        assertEquals(true, provider.invoke("runtime.ping").getBoolean("ready"))
    }

    @Test fun `fetch bridges OkHttp with headers bodies and UTF-8`() = runBlocking {
        val url = "http://127.0.0.1:${server.address.port}/echo"
        val result = provider.invoke("runtime.fetch", JSONObject()
            .put("url", url)
            .put("init", JSONObject().put("method", "post").put("body", "café")
                .put("headers", JSONObject().put("X-Test", "yes"))))
        assertEquals(201, result.getInt("status"))
        assertEquals("POST yes café 🎵", result.getString("body"))
        assertEquals("a=1\nb=2", result.getJSONObject("headers").getString("set-cookie"))
    }

    @Test fun `unknown methods and transport failures reject`() = runBlocking {
        try {
            provider.invoke("missing.method")
            fail()
        } catch (error: ProviderException) {
            assertTrue(error.message.orEmpty().contains("Unknown YouTube provider method"))
        }
        try {
            provider.invoke("runtime.fetch", JSONObject().put("url", "http://127.0.0.1:1/"))
            fail()
        } catch (error: ProviderException) {
            assertTrue(error.message.orEmpty().isNotEmpty())
        }
    }
}
