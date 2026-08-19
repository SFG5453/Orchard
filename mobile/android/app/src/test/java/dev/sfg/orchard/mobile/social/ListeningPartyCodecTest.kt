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

package dev.sfg.orchard.mobile.social

import dev.sfg.orchard.mobile.model.Track
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire shape against the web client.
 *
 * The two ends name three fields differently, and nothing in either type system catches a
 * disagreement — a wrong key travels fine and simply arrives blank. These tests are the only place
 * that mismatch is visible before a device is in someone's hand.
 */
class ListeningPartyCodecTest {
    private val track = Track(
        id = "abc123",
        title = "Song",
        artist = "Artist",
        album = "Record",
        albumId = "MPREb_1",
        artworkUrl = "https://example.test/cover.jpg",
        durationMs = 214_000,
        explicit = true,
    )

    @Test
    fun encodesTheFieldNamesTheWebClientReads() {
        val json = PartyTrackJson.encode(track)

        // `src/app/platform/connectActions.js` reads exactly these off a track.
        assertEquals("abc123", json.getString("id"))
        assertEquals("Song", json.getString("title"))
        assertEquals("Artist", json.getString("artist"))
        assertEquals("https://example.test/cover.jpg", json.getString("thumbnail"))
        assertEquals(214.0, json.getDouble("durationSeconds"), 0.001)
    }

    @Test
    fun encodesAnIdSoTheWebNeverReadsTheTrackAsAnAlbum() {
        val json = PartyTrackJson.encode(track)

        // `isAlbumItem` in browseActions.js treats `albumId && !id` as an album, and an album is
        // not playable — it would be opened as a page instead of played.
        assertTrue(json.getString("id").isNotBlank())
        assertEquals("song", json.getString("type"))
    }

    @Test
    fun readsAWebTrackThatOnlyUsesWebFieldNames() {
        val fromWeb = JSONObject()
            .put("id", "xyz789")
            .put("title", "Web Song")
            .put("subtitle", "Web Artist")
            .put("thumbnail", "https://example.test/web.jpg")
            .put("durationSeconds", 180.5)

        val decoded = PartyTrackJson.decode(fromWeb)

        assertEquals("xyz789", decoded.id)
        assertEquals("Web Song", decoded.title)
        // The web fills the artist under `artist`, `subtitle`, or `artists[0]` depending on source.
        assertEquals("Web Artist", decoded.artist)
        assertEquals("https://example.test/web.jpg", decoded.artworkUrl)
        assertEquals(180_500L, decoded.durationMs)
    }

    @Test
    fun readsAnArtistFromTheArrayFormTheWebSometimesSends() {
        val fromWeb = JSONObject()
            .put("id", "xyz789")
            .put("title", "Web Song")
            .put("artists", JSONArray().put("First").put("Second"))

        assertEquals("First", PartyTrackJson.decode(fromWeb).artist)
    }

    @Test
    fun roundTripsAMobileTrackWithoutLoss() {
        assertEquals(track, PartyTrackJson.decode(PartyTrackJson.encode(track)))
    }

    @Test
    fun doesNotTurnAJsonNullIntoTheLiteralNullString() {
        val payload = JSONObject().put("id", "abc").put("title", JSONObject.NULL)

        assertEquals("Unknown track", PartyTrackJson.decode(payload).title)
    }

    @Test
    fun sendsTimesInSecondsBecauseTheWebAddsDriftToThem() {
        val state = PartyPlaybackState(
            track = track,
            isPlaying = true,
            currentTime = 42.5,
            duration = 214.0,
            sentAt = 1_700_000_000_000,
        )

        val json = PartyStateJson.encode(state)

        // applyListeningPartyState computes `(Date.now() - sentAt) / 1000` and adds it to
        // currentTime. Milliseconds here would put every guest far past the end of the track.
        assertEquals(42.5, json.getDouble("currentTime"), 0.001)
        assertEquals(1_700_000_000_000, json.getLong("sentAt"))
    }

    @Test
    fun treatsAMissingTimestampAsNowRatherThanTheEpoch() {
        val decoded = PartyStateJson.decode(JSONObject().put("currentTime", 10.0))
        val skew = System.currentTimeMillis() - decoded.sentAt

        assertTrue("sentAt should default to roughly now, was ${decoded.sentAt}", skew in 0..5_000)
    }

    @Test
    fun dropsATrackWithNoIdBecauseItCannotBePlayed() {
        val payload = JSONObject().put("track", JSONObject().put("title", "Nameless"))

        assertNull(PartyStateJson.decode(payload).track)
    }

    @Test
    fun capsTheQueueAndHistoryAtTheLengthsTheWebAccepts() {
        val many = (1..200).map { Track(id = "t$it", title = "T$it", artist = "A") }
        val state = PartyPlaybackState(track = track, queue = many, history = many)

        val decoded = PartyStateJson.decode(PartyStateJson.encode(state))

        assertEquals(PartyStateJson.MAX_QUEUE, decoded.queue.size)
        assertEquals(PartyStateJson.MAX_HISTORY, decoded.history.size)
    }

    @Test
    fun normalisesTypedRoomCodesTheWayTheWorkerDoes() {
        assertEquals("ABC234", cleanRoomCode("  abc234 "))
        assertEquals("ABC234", cleanRoomCode("abc-234"))
        // The worker's filter keeps every A-Z. Stripping the look-alike letters here would shift
        // the rest of the code left and turn a typo into a different valid-looking room.
        assertEquals("AIO23", cleanRoomCode("aio23"))
        assertEquals("", cleanRoomCode("!!!"))
    }

    @Test
    fun stripsTheDigitsTheRoomAlphabetNeverUses() {
        assertFalse(cleanRoomCode("A0B1C").contains("0"))
        assertFalse(cleanRoomCode("A0B1C").contains("1"))
    }
}
