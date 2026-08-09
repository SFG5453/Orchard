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

package dev.sfg.orchard.mobile.lyrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsParserTest {
    @Test
    fun synchronizedLyricsAreSortedAndReceiveEndTimes() {
        val lines = LyricsParser.lrc("[00:12.50]Second\n[00:01.25]First")

        assertEquals(listOf("First", "Second"), lines.map { it.text })
        assertEquals(1_250L, lines[0].startMs)
        assertEquals(12_500L, lines[0].endMs)
        assertEquals(null, lines[1].endMs)
    }

    @Test
    fun plainLyricsDiscardBlankLines() {
        assertEquals(listOf("First", "Second"), LyricsParser.plain(" First \n\n Second ").map { it.text })
    }

    @Test
    fun lyricsPlusPayloadPreservesMillisecondTiming() {
        val root = JSONObject(
            """{"lyrics":[
              {"time":513,"duration":1703,"text":"I don't stay in them projects","syllabus":[
                {"text":"I ","time":513,"duration":144},
                {"text":"don't ","time":657,"duration":128},
                {"text":"stay","time":785,"duration":136}
              ]},
              {"time":2217,"duration":1687,"syllabus":[{"text":"Second "},{"text":"line"}]}
            ]}""",
        )

        val lines = LyricsParser.amPayload(root)

        assertEquals(listOf("I don't stay in them projects", "Second line"), lines.map { it.text })
        assertEquals(513L, lines[0].startMs)
        assertEquals(2_216L, lines[0].endMs)
        assertEquals(2_217L, lines[1].startMs)
        assertEquals(listOf("I", "don't", "stay"), lines[0].words.map { it.text })
        assertEquals(listOf(513L, 657L, 785L), lines[0].words.map { it.startMs })
    }

    @Test
    fun leadingSpaceSyllablesStillSplitIntoWords() {
        val root = JSONObject(
            """{"lyrics":[
              {"time":100,"duration":900,"syllabus":[
                {"text":"I","time":100,"duration":100},
                {"text":" walked","time":200,"duration":300},
                {"text":" alone","time":500,"duration":400}
              ]}
            ]}""",
        )

        val lines = LyricsParser.amPayload(root)

        assertEquals("I walked alone", lines[0].text)
        assertEquals(listOf("I", "walked", "alone"), lines[0].words.map { it.text })
        assertEquals(listOf(100L, 200L, 500L), lines[0].words.map { it.startMs })
    }

    @Test
    fun appleTtmlLineTimingIsNormalized() {
        val lines = LyricsParser.ttml(
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
              <p begin="1.2s" end="3.5s"><span begin="1.2s">Hello </span><span begin="2s">world</span></p>
            </div></body></tt>""",
        )

        assertEquals("Hello world", lines.single().text)
        assertEquals(1_200L, lines.single().startMs)
        assertEquals(3_500L, lines.single().endMs)
        assertEquals(listOf("Hello", "world"), lines.single().words.map { it.text })
        assertEquals(listOf(1_200L, 2_000L), lines.single().words.map { it.startMs })
    }

    @Test
    fun instrumentalLrclibResponseIsNotReportedAsMissing() {
        assertEquals(
            listOf("Instrumental"),
            LyricsParser.lrcLib(JSONObject().put("instrumental", true)).map { it.text },
        )
    }
}
