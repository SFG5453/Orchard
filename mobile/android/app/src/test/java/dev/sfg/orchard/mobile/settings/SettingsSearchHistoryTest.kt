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

package dev.sfg.orchard.mobile.settings

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchHistoryTest {

    @Test
    fun `decodeHistory correctly parses JSON array strings`() {
        val json = """["Kendrick Lamar","blonde","The Weeknd","David Bowie","Chill playlists"]"""
        val items = decodeSearchHistory(json)

        assertEquals(
            listOf("Kendrick Lamar", "blonde", "The Weeknd", "David Bowie", "Chill playlists"),
            items,
        )
    }

    @Test
    fun `decodeHistory filters blank entries and handles invalid json`() {
        val jsonWithBlanks = """["Kendrick Lamar","", "   ", "The Weeknd"]"""
        assertEquals(listOf("Kendrick Lamar", "The Weeknd"), decodeSearchHistory(jsonWithBlanks))

        assertEquals(emptyList<String>(), decodeSearchHistory(""))
        assertEquals(emptyList<String>(), decodeSearchHistory("not valid json"))
    }

    @Test
    fun `removing search history item removes only the targeted query case-insensitively`() {
        val initial = listOf("Kendrick Lamar", "blonde", "The Weeknd", "David Bowie", "Chill playlists")
        val targetToRemove = "blonde"

        val updated = initial.filterNot { it.equals(targetToRemove, ignoreCase = true) }
        val serialized = JSONArray(updated).toString()
        val redecoded = decodeSearchHistory(serialized)

        assertEquals(listOf("Kendrick Lamar", "The Weeknd", "David Bowie", "Chill playlists"), redecoded)
    }

    @Test
    fun `removing non-existent search item leaves history unchanged`() {
        val initial = listOf("Kendrick Lamar", "blonde")
        val updated = initial.filterNot { it.equals("Drake", ignoreCase = true) }

        assertEquals(initial, updated)
    }
}
