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

package dev.sfg.orchard.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDialogParserTest {

    @Test
    fun parsesMultiSectionMarkdown() {
        val markdown = """
            ## Orchard Mobile 1.1.0 "Praise Perceived"

            ### New & improved
            - Smart Crossfade low-frequency bass handoff
            - Added persistent text scale slider
            - Support for `video-bound` playback

            ### Fixed
            - Restored direct YouTube Music playback
            - Fixed crash when resuming playback

            ### Changed
            - Removed legacy setup guide
        """.trimIndent()

        val sections = parseReleaseNoteSections(markdown)

        assertEquals(3, sections.size)
        assertEquals("New & improved", sections[0].title)
        assertEquals(ReleaseNoteCategory.NEW, sections[0].category)
        assertEquals(3, sections[0].items.size)
        assertEquals("Smart Crossfade low-frequency bass handoff", sections[0].items[0])
        assertEquals("Support for `video-bound` playback", sections[0].items[2])

        assertEquals("Fixed", sections[1].title)
        assertEquals(ReleaseNoteCategory.FIXED, sections[1].category)
        assertEquals(2, sections[1].items.size)
        assertEquals("Restored direct YouTube Music playback", sections[1].items[0])

        assertEquals("Changed", sections[2].title)
        assertEquals(ReleaseNoteCategory.CHANGED, sections[2].category)
        assertEquals(1, sections[2].items.size)
    }

    @Test
    fun parsesBoldSectionHeaders() {
        val markdown = """
            **New & improved**
            - Feature A
            - Feature B

            **Fixed:**
            - Bug fix 1
        """.trimIndent()

        val sections = parseReleaseNoteSections(markdown)

        assertEquals(2, sections.size)
        assertEquals("New & improved", sections[0].title)
        assertEquals(2, sections[0].items.size)
        assertEquals("Fixed", sections[1].title)
        assertEquals(1, sections[1].items.size)
    }

    @Test
    fun parsesUnsectionedBulletList() {
        val markdown = """
            - Added age-gated stream support
            - Added playlist creation
            - Added song sharing
        """.trimIndent()

        val sections = parseReleaseNoteSections(markdown)

        assertEquals(1, sections.size)
        assertEquals("What's new", sections[0].title)
        assertEquals(3, sections[0].items.size)
        assertEquals("Added age-gated stream support", sections[0].items[0])
    }

    @Test
    fun categorizesSecurityAndOtherSections() {
        assertEquals(ReleaseNoteCategory.SECURITY, categorizeSection("Security Updates"))
        assertEquals(ReleaseNoteCategory.NEW, categorizeSection("Added features"))
        assertEquals(ReleaseNoteCategory.FIXED, categorizeSection("Bug Fixes & Patches"))
        assertEquals(ReleaseNoteCategory.CHANGED, categorizeSection("Maintenance"))
        assertEquals(ReleaseNoteCategory.OTHER, categorizeSection("Notes"))
    }

    @Test
    fun formatsMarkdownInlineText() {
        val annotated = formatMarkdownInline("Support for **bold words** and `code span` items")
        assertTrue(annotated.text.contains("Support for bold words and code span items"))
        assertTrue(annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun parsesActualMobileReleaseNotes() {
        val notes = dev.sfg.orchard.mobile.MobileChangelog.CURRENT_RELEASE_NOTES
        val sections = parseReleaseNoteSections(notes)

        assertEquals(4, sections.size)
        assertEquals("Added", sections[0].title)
        assertEquals(ReleaseNoteCategory.NEW, sections[0].category)
        assertEquals(3, sections[0].items.size)

        assertEquals("Changed", sections[1].title)
        assertEquals(ReleaseNoteCategory.CHANGED, sections[1].category)
        assertEquals(3, sections[1].items.size)

        assertEquals("Fixed", sections[2].title)
        assertEquals(ReleaseNoteCategory.FIXED, sections[2].category)
        assertEquals(1, sections[2].items.size)

        assertEquals("Maintenance", sections[3].title)
        assertEquals(ReleaseNoteCategory.CHANGED, sections[3].category)
        assertEquals(1, sections[3].items.size)
    }

    @Test
    fun handlesEmptyOrBlankReleaseNotes() {
        assertTrue(parseReleaseNoteSections("").isEmpty())
        assertTrue(parseReleaseNoteSections("   \n\n  ").isEmpty())
        assertTrue(formatMarkdownInline("").text.isEmpty())
    }
}
