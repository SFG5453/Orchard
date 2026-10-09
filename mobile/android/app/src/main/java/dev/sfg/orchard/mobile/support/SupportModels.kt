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

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/** One mirrored GitHub timeline item: a reply, a commit mentioning the report, a close. */
data class SupportEvent(
    val key: String,
    val kind: String,
    val actor: String,
    val actorAvatar: String,
    val maintainer: Boolean,
    val reporter: Boolean,
    val title: String,
    val body: String,
    val url: String,
    val createdAt: Long,
)

data class SupportReport(
    val id: String,
    val number: Int,
    val url: String,
    val kind: String,
    val title: String,
    val state: String,
    val stateReason: String,
    val createdAt: Long,
    val updatedAt: Long,
    val unread: Int,
    val latest: SupportEvent?,
) {
    val closed get() = state == "closed"
}

data class GithubLink(val login: String, val avatar: String)

data class SupportList(
    val github: GithubLink?,
    val repository: String,
    val unread: Int,
    val reports: List<SupportReport>,
)

/** In-app alert; [reportId] opens that report when tapped. */
data class SupportNotice(val message: String, val reportId: String?)

data class SupportDraft(
    val kind: String = "bug",
    val title: String = "",
    val body: String = "",
    val diagnostics: Boolean = true,
    val screenshot: Bitmap? = null,
)

data class SupportState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val github: GithubLink? = null,
    val linking: Boolean = false,
    val repository: String = "",
    val reports: List<SupportReport> = emptyList(),
    val unread: Int = 0,
    val active: SupportReport? = null,
    val events: List<SupportEvent> = emptyList(),
    val activeLoading: Boolean = false,
    val submitting: Boolean = false,
    val error: String = "",
    val draft: SupportDraft = SupportDraft(),
)

/** The account service's JSON, decoded. Kept pure so it runs in unit tests. */
object SupportJson {
    fun event(json: JSONObject) = SupportEvent(
        key = json.optString("key"),
        kind = json.optString("kind"),
        actor = json.optString("actor"),
        actorAvatar = json.optString("actor_avatar"),
        maintainer = json.optBoolean("maintainer"),
        reporter = json.optBoolean("reporter"),
        title = json.optString("title"),
        body = json.optString("body"),
        url = json.optString("url"),
        createdAt = json.optLong("created_at"),
    )

    fun report(json: JSONObject) = SupportReport(
        id = json.optString("id"),
        number = json.optInt("number"),
        url = json.optString("url"),
        kind = json.optString("kind"),
        title = json.optString("title"),
        state = json.optString("state", "open"),
        stateReason = json.optString("state_reason"),
        createdAt = json.optLong("created_at"),
        updatedAt = json.optLong("updated_at"),
        unread = json.optInt("unread"),
        latest = json.optJSONObject("latest")?.let(::event),
    )

    fun list(json: JSONObject) = SupportList(
        github = json.optJSONObject("github")?.let { GithubLink(it.optString("login"), it.optString("avatar")) }
            ?.takeIf { it.login.isNotBlank() },
        repository = json.optString("repository"),
        unread = json.optInt("unread"),
        reports = json.optJSONArray("reports").objects().map(::report),
    )

    fun events(json: JSONObject) = json.optJSONArray("events").objects().map(::event)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

/**
 * What changed between two polls, as one line for the banner. The first load
 * only reports a total, so starting the app does not replay old news one by one.
 */
fun summarizeUpdates(previous: List<SupportReport>, current: List<SupportReport>, firstLoad: Boolean): SupportNotice? {
    if (firstLoad) {
        val total = current.sumOf { it.unread }
        if (total == 0) return null
        val only = current.singleOrNull { it.unread > 0 }
        val text = if (total == 1) "1 new update on your bug reports" else "$total new updates on your bug reports"
        return SupportNotice(text, only?.id)
    }
    val before = previous.associate { it.id to it.unread }
    val changed = current.filter { it.unread > (before[it.id] ?: 0) }
    return when {
        changed.isEmpty() -> null
        changed.size == 1 -> changed[0].let { SupportNotice("“${it.title}”: ${it.latest?.title.orEmpty()}".trimEnd(' ', ':'), it.id) }
        else -> SupportNotice("${changed.size} of your bug reports have updates", null)
    }
}
