/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

package dev.sfg.orchard.mobile.widget

import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchardWidgetStateTest {
    @Test
    fun `widget state round trips playback and recent tracks`() {
        val current = Track(
            id = "current",
            title = "Sunlit Static",
            artist = "The Orchard",
            album = "Canopy",
            artworkUrl = "https://example.com/current.jpg",
            durationMs = 213_000,
        )
        val recent = (1..6).map { index ->
            Track(id = "recent-$index", title = "Recent $index", artist = "Artist $index")
        }
        val encoded = OrchardWidgetStateCodec.encode(
            OrchardWidgetState(
                currentTrack = current,
                recentlyPlayed = recent,
                isPlaying = true,
                positionMs = 42_000,
                durationMs = 213_000,
            )
        )

        val decoded = OrchardWidgetStateCodec.decode(encoded)

        assertEquals(current, decoded.currentTrack)
        assertEquals(recent.take(4), decoded.recentlyPlayed)
        assertTrue(decoded.isPlaying)
        assertEquals(42_000, decoded.positionMs)
        assertEquals(213_000, decoded.durationMs)
    }

    @Test
    fun `negative positions are clamped at the storage boundary`() {
        val decoded = OrchardWidgetStateCodec.decode(
            OrchardWidgetStateCodec.encode(
                OrchardWidgetState(positionMs = -10, durationMs = -20, isPlaying = false)
            )
        )

        assertEquals(0, decoded.positionMs)
        assertEquals(0, decoded.durationMs)
        assertFalse(decoded.isPlaying)
    }
}
