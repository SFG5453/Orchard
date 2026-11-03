/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.sfg.orchard.mobile.lyrics

import dev.sfg.orchard.mobile.model.LyricsProvider
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.LyricsProviderConfig
import dev.sfg.orchard.mobile.model.visibleLyricsProviders
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricsRepositoryTest {
    private val track = Track(id = "abcdefghijk", title = "Song", artist = "Artist")

    @Test
    fun appleLyricsUseTrackIdentityAndKeepWordTiming() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("No mirror should be called") }.build()
        val appleTrack = track.copy(id = "am.songs.123", title = "", artist = "")
        var calls = 0
        val repository = LyricsRepository(client, appleLyrics = {
            calls++
            assertEquals(appleTrack, it)
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="3s"><span begin="1s" end="2s">Found</span> <span begin="2s" end="3s">lyrics</span></p></div></body></tt>"""
        }) { error("YouTube must not be called") }
        val lines = repository.lyrics(appleTrack, listOf(LyricsProvider.APPLE))
        assertEquals(1000L, lines.single().startMs)
        assertEquals(2, lines.single().words.size)
        assertEquals(LyricsProvider.APPLE, repository.sources.value[appleTrack.id])
        assertEquals(lines, repository.lyrics(appleTrack, listOf(LyricsProvider.APPLE)))
        assertEquals(1, calls)
    }

    @Test
    fun appleTranslationOnlyAppliesToLyricsShownFromApple() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("No mirror should be called") }.build()
        val appleTrack = track.copy(id = "am.songs.123")
        val asked = mutableListOf<Pair<String, String>>()
        val repository = LyricsRepository(client,
            appleLyrics = { """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="3s"><span>Found</span></p></div></body></tt>""" },
            appleTranslation = { shown, language -> asked += shown.id to language; mapOf("L1" to "Translated") },
        ) { "" }

        assertEquals(emptyMap<String, String>(), repository.appleTranslation(appleTrack.id, "en-US"))
        repository.lyrics(appleTrack, listOf(LyricsProvider.APPLE))
        assertEquals(mapOf("L1" to "Translated"), repository.appleTranslation(appleTrack.id, "en-US"))
        assertEquals(listOf(appleTrack.id to "en-US"), asked)
        assertEquals(emptyMap<String, String>(), repository.appleTranslation("other", "en-US"))
    }

    @Test
    fun missingOrFailedAppleLyricsFallThroughWithoutCallingMirrors() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("No mirror should be called") }.build()
        val configs = LyricsProvider.entries.map { LyricsProviderConfig(it, it != LyricsProvider.LRCLIB) }
        val providers = visibleLyricsProviders(configs, true).filter { it.enabled }.map { it.provider }
        for (failed in listOf(false, true)) {
            val repository = LyricsRepository(client, appleLyrics = {
                if (failed) error("Apple unavailable") else ""
            }) { "YouTube fallback" }
            assertEquals("YouTube fallback", repository.lyrics(track, providers).single().text)
            assertEquals(LyricsProvider.YOUTUBE, repository.sources.value[track.id])
        }
    }

    @Test
    fun betterLyricsIsIndependentFromBiniAndOtherProviders() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("lyrics-api.boidu.dev", chain.request().url.host)
            val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="3s">Found lyrics</p></div></body></tt>"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("ttml", ttml).toString().toResponseBody()).build()
        }.build()
        val repository = LyricsRepository(client) { error("YouTube must not be called") }
        val lines = repository.lyrics(track, listOf(LyricsProvider.BETTER_LYRICS))
        assertEquals("Found lyrics", lines.single().text)
        assertEquals(1000L, lines.single().startMs)
        assertEquals(LyricsProvider.BETTER_LYRICS, repository.sources.value[track.id])
        assertEquals(lines, repository.lyrics(track, listOf(LyricsProvider.BETTER_LYRICS)))
        assertEquals(1, requests)
        assertTrue(repository.lyrics(track, emptyList()).isEmpty())
        assertNull(repository.sources.value[track.id])
    }

    @Test
    fun youtubeCanBeSelectedWithoutMetadataOrNetworkFallbacks() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("No HTTP lyrics provider should be called") }.build()
        var fetchedId = ""
        val repository = LyricsRepository(client) { id -> fetchedId = id; "Plain lyrics" }
        val lines = repository.lyrics(track.copy(artist = ""), listOf(LyricsProvider.YOUTUBE))
        assertEquals(track.id, fetchedId)
        assertEquals("Plain lyrics", lines.single().text)
        assertEquals(LyricsProvider.YOUTUBE, repository.sources.value[track.id])
    }

    @Test
    fun songOverrideDoesNotChangeOtherSongsAndCanBeReset() {
        val repository = LyricsRepository(OkHttpClient()) { "" }
        repository.selectProvider(track.id, LyricsProvider.LRCLIB)
        repository.selectProvider("another-song", LyricsProvider.BETTER_LYRICS)
        assertEquals(LyricsProvider.LRCLIB, repository.providerOverrides.value[track.id])
        repository.selectProvider(track.id, null)
        assertNull(repository.providerOverrides.value[track.id])
        assertEquals(LyricsProvider.BETTER_LYRICS, repository.providerOverrides.value["another-song"])
    }
}
