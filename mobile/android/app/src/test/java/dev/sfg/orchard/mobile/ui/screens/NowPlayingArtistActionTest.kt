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

package dev.sfg.orchard.mobile.ui.screens

import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingArtistActionTest {
    @Test
    fun multipleArtistsOpenSelectionDialog() {
        var dialogOpened = false
        val openedCollections = mutableListOf<String>()
        val track = Track(
            id = "track",
            title = "Duet",
            artist = "One & Two",
            artists = listOf(Artist("UC1", "One"), Artist("UC2", "Two")),
        )

        val action = artistOpenAction(track, openedCollections::add) { dialogOpened = true }
        action?.invoke()

        assertTrue(dialogOpened)
        assertTrue(openedCollections.isEmpty())
    }

    @Test
    fun singleArtistOpensArtistDirectly() {
        var dialogOpened = false
        val openedCollections = mutableListOf<String>()
        val track = Track(
            id = "track",
            title = "Solo",
            artist = "One",
            artists = listOf(Artist("UC1", "One")),
        )

        val action = artistOpenAction(track, openedCollections::add) { dialogOpened = true }
        action?.invoke()

        assertFalse(dialogOpened)
        assertEquals(listOf("UC1"), openedCollections)
    }

    @Test
    fun invalidAndDuplicateArtistsDoNotCreateExtraChoices() {
        val track = Track(
            id = "track",
            title = "Solo",
            artist = "One",
            artists = listOf(
                Artist("", "Missing"),
                Artist("UC1", "One"),
                Artist("UC1", "One again"),
            ),
        )

        assertEquals(listOf(Artist("UC1", "One")), selectableTrackArtists(track))
    }
}
