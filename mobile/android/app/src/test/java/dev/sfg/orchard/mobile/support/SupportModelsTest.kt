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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.support

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportModelsTest {
    private val listJson = JSONObject(
        """
        {"github":{"id":"4242","login":"octo","avatar":"https://a/o"},"repository":"SFG5453/orchard-v4","unread":2,
         "reports":[{"id":"r1","number":12,"url":"https://github.com/x/12","kind":"bug","title":"Crash on start",
           "state":"closed","state_reason":"completed","created_at":1,"updated_at":2,"unread":2,
           "latest":{"key":"closed:5","kind":"closed","actor":"sfg","actor_avatar":"","maintainer":false,"reporter":false,
             "title":"Fixed by commit abc1234","body":"","url":"https://github.com/c","created_at":3}}]}
        """.trimIndent(),
    )

    private fun report(id: String, unread: Int, latest: String = "") =
        SupportJson.report(JSONObject().put("id", id).put("title", "Report $id").put("unread", unread)
            .put("latest", JSONObject().put("title", latest)))

    @Test
    fun parsesTheReportList() {
        val list = SupportJson.list(listJson)
        assertEquals("octo", list.github?.login)
        assertEquals(2, list.unread)
        val report = list.reports.single()
        assertEquals(12, report.number)
        assertEquals(true, report.closed)
        assertEquals("Fixed by commit abc1234", report.latest?.title)
    }

    @Test
    fun missingGithubMeansUnlinked() {
        assertNull(SupportJson.list(JSONObject().put("github", JSONObject.NULL)).github)
    }

    @Test
    fun firstLoadSummarizesAndPointsAtTheOnlyReport() {
        val notice = summarizeUpdates(emptyList(), SupportJson.list(listJson).reports, firstLoad = true)
        assertEquals("2 new updates on your bug reports", notice?.message)
        assertEquals("r1", notice?.reportId)
        assertNull(summarizeUpdates(emptyList(), listOf(report("a", 0)), firstLoad = true))
    }

    @Test
    fun laterPollsNameTheChange() {
        val notice = summarizeUpdates(listOf(report("a", 0)), listOf(report("a", 1, "sfg replied")), firstLoad = false)
        assertEquals("“Report a”: sfg replied", notice?.message)
        assertEquals("a", notice?.reportId)
        assertNull(summarizeUpdates(listOf(report("a", 1)), listOf(report("a", 1)), firstLoad = false))
    }

    @Test
    fun severalChangesCollapse() {
        val notice = summarizeUpdates(
            listOf(report("a", 0), report("b", 0)),
            listOf(report("a", 1), report("b", 3)),
            firstLoad = false,
        )
        assertEquals("2 of your bug reports have updates", notice?.message)
        assertNull(notice?.reportId)
    }

    @Test
    fun multipartEndsWithTheClosingBoundary() {
        val form = MultipartForm().field("kind", "bug").file("screenshot", "s.png", "image/png", byteArrayOf(1, 2))
        val text = String(form.build())
        assert(text.contains("name=\"kind\"\r\n\r\nbug\r\n"))
        assert(text.contains("Content-Type: image/png\r\n\r\n"))
        assert(text.endsWith("--${form.boundary}--\r\n"))
    }
}
