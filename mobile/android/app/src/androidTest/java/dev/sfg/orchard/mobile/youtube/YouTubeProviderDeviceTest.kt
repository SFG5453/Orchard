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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.auth.AuthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End to end on a device: the stored session, desktop provider bytecode, QuickJS and the CDN. */
@RunWith(AndroidJUnit4::class)
class YouTubeProviderDeviceTest {
    private val graph = OrchardGraph.from(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun <T> timed(label: String, block: () -> T): T {
        val started = System.nanoTime()
        return block().also { Log.i(TAG, "$label took ${(System.nanoTime() - started) / 1_000_000} ms") }
    }

    @Test fun storedAccountProfileResolves(): Unit = runBlocking {
        withTimeout(15_000) { graph.auth.state.first { it !is AuthState.Restoring } }
        val session = graph.auth.session() ?: return@runBlocking
        val profile = graph.youtube.invoke("account.profile", JSONObject().put("session", session.providerJson()))
        assertTrue("YouTube account name is missing", profile.text("name").isNotBlank())
        assertTrue("YouTube account avatar is missing", profile.text("avatarUrl").isNotBlank())
        val refreshed = withTimeout(15_000) {
            graph.auth.state.first { it is AuthState.SignedIn && it.avatarUrl.isNotBlank() }
        } as AuthState.SignedIn
        assertEquals(profile.text("name"), refreshed.displayName)
        assertEquals(profile.text("avatarUrl"), refreshed.avatarUrl)
    }

    @Test fun signedInCatalogAndStreamResolveThroughQuickJs(): Unit = runBlocking {
        withTimeout(15_000) { graph.auth.state.first { it !is AuthState.Restoring } }
        assumeTrue("No stored YouTube session on this device", graph.auth.session() != null)

        val home = timed("catalog.home") { runBlocking { graph.catalog.home() } }
        Log.i(TAG, "home: ${home.sections.size} sections, ${home.artists.size} artists")
        assertTrue(home.sections.isNotEmpty())

        val results = timed("catalog.search") { runBlocking { graph.catalog.search("SZA Snooze") } }
        val track = results.tracks.first()
        Log.i(TAG, "search: ${track.title} by ${track.artist} (${track.id})")

        val stream = timed("playback.resolve (cold)") { graph.streams.resolve(track) }
        timed("playback.resolve (cached)") { graph.streams.resolve(track) }
        Log.i(TAG, "stream: ${stream.mimeType} ${stream.bitrateKbps} kbps, ${stream.contentLength} bytes")
        assertTrue(stream.url.contains("googlevideo.com"))

        // A whole megabyte past the first, where a missing PO token starts being refused.
        val request = Request.Builder().url(stream.url)
            .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
            .header("Range", "bytes=1048576-2097151")
            .build()
        timed("media range") {
            graph.http.newCall(request).execute().use { response ->
                assertEquals(206, response.code)
                assertEquals(1_048_576, response.body.bytes().size)
            }
        }

        // Warm path: player and PO minter already loaded. The provider reports its own stages.
        val next = results.tracks.getOrNull(1) ?: return@runBlocking
        val warm = timed("playback.resolve (warm)") {
            runBlocking {
                graph.youtube.invoke("playback.resolve", JSONObject()
                    .put("session", graph.auth.session().providerJson())
                    .put("track", JSONObject().put("id", next.id).put("type", "track")
                        .put("title", next.title).put("artist", next.artist)))
            }
        }
        Log.i(TAG, "warm stages: ${warm.optJSONObject("timings")}")
    }

    private companion object {
        const val TAG = "ProviderDeviceTest"
    }
}
