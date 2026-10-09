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

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.net.toUri
import dev.sfg.orchard.mobile.auth.OrchardAccountService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Bug reports through the Orchard account service. Each report is a GitHub
 * issue; this mirrors its activity so the app can say when someone answers.
 * Same API as the desktop SupportCenter.
 */
class SupportService private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val account = OrchardAccountService.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SupportState())
    val state = _state.asStateFlow()
    private val _notices = MutableSharedFlow<SupportNotice>(extraBufferCapacity = 4)
    val notices = _notices.asSharedFlow()
    // Bumped on sign-out so late replies cannot refill a cleared state.
    private val generation = AtomicLong()
    private var linkJob: Job? = null
    @Volatile private var lastRefresh = 0L

    init {
        scope.launch {
            account.state.map { it.signedIn }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) refresh() else clear()
            }
        }
    }

    private suspend fun <T> call(block: suspend (token: String) -> T): T? {
        val token = account.accessToken() ?: return null
        return block(token)
    }

    private fun failure(e: Exception): String = when (e) {
        is SupportHttpException -> e.message.orEmpty()
        else -> "Could not reach the Orchard account service."
    }

    /** [minAgeMs] skips the call when the last refresh is newer, for lifecycle resumes. */
    fun refresh(minAgeMs: Long = 0) {
        if (!account.state.value.signedIn || _state.value.loading) return
        if (System.currentTimeMillis() - lastRefresh < minAgeMs && !_state.value.linking) return
        val attempt = generation.get()
        _state.update { it.copy(loading = true) }
        scope.launch {
            try {
                val list = call { SupportJson.list(supportRequest(account.serviceUrl, "GET", "/support/reports", it)) } ?: return@launch
                if (generation.get() != attempt) return@launch
                lastRefresh = System.currentTimeMillis()
                apply(list)
            } catch (_: Exception) {
                // Background polls stay quiet; user actions report their own errors.
            } finally {
                if (generation.get() == attempt) _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun apply(list: SupportList) {
        val before = _state.value
        val justLinked = before.linking && list.github != null
        if (justLinked) {
            linkJob?.cancel()
            _notices.tryEmit(SupportNotice("GitHub linked as @${list.github.login}.", null))
        }
        _state.update {
            it.copy(
                loaded = true, github = list.github, linking = it.linking && list.github == null,
                repository = list.repository, reports = list.reports, unread = list.unread,
            )
        }
        summarizeUpdates(before.reports, list.reports, !before.loaded)?.let { _notices.tryEmit(it) }
        // News on the open report: pull it in, which also clears its badge.
        val active = before.active
        if (active != null && list.reports.any { it.id == active.id && it.unread > 0 }) openReport(active.id)
    }

    fun openReport(id: String) {
        val attempt = generation.get()
        _state.update { state ->
            state.copy(active = state.reports.firstOrNull { it.id == id } ?: state.active?.takeIf { it.id == id },
                activeLoading = true, error = "")
        }
        scope.launch {
            try {
                val json = call { supportRequest(account.serviceUrl, "GET", "/support/reports/$id", it) } ?: return@launch
                if (generation.get() != attempt || _state.value.active?.id != id && _state.value.active != null) return@launch
                val report = SupportJson.report(json.getJSONObject("report"))
                _state.update { it.copy(active = report, events = SupportJson.events(json)) }
                if (report.unread > 0) markRead(id)
            } catch (e: Exception) {
                if (generation.get() == attempt) _state.update { it.copy(error = failure(e)) }
            } finally {
                if (generation.get() == attempt) _state.update { it.copy(activeLoading = false) }
            }
        }
    }

    fun closeReport() = _state.update { it.copy(active = null, events = emptyList(), activeLoading = false) }

    private fun markRead(id: String) {
        _state.update { state ->
            val reports = state.reports.map { if (it.id == id) it.copy(unread = 0) else it }
            state.copy(reports = reports, unread = reports.sumOf { it.unread }, active = state.active?.copy(unread = 0))
        }
        scope.launch { runCatching { call { supportRequest(account.serviceUrl, "POST", "/support/reports/$id/read", it) } } }
    }

    fun linkGithub() {
        _state.update { it.copy(error = "") }
        scope.launch {
            try {
                val url = call { supportRequest(account.serviceUrl, "POST", "/github/link", it, "{}".toByteArray()) }
                    ?.optString("url").orEmpty()
                if (url.isBlank()) return@launch
                _state.update { it.copy(linking = true) }
                appContext.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                linkJob?.cancel()
                // The browser hands nothing back to the app; poll until the link shows up.
                linkJob = scope.launch {
                    repeat(LINK_POLLS) {
                        delay(LINK_POLL_MS)
                        if (!_state.value.linking) return@launch
                        refresh()
                    }
                    _state.update { it.copy(linking = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = failure(e)) }
            }
        }
    }

    fun cancelGithubLink() {
        linkJob?.cancel()
        _state.update { it.copy(linking = false) }
    }

    fun unlinkGithub() {
        scope.launch {
            try {
                call { supportRequest(account.serviceUrl, "DELETE", "/github", it) } ?: return@launch
                _state.update { it.copy(github = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = failure(e)) }
            }
        }
    }

    fun updateDraft(transform: (SupportDraft) -> SupportDraft) = _state.update { it.copy(draft = transform(it.draft), error = "") }

    fun attachScreenshot(bitmap: Bitmap?) = updateDraft { it.copy(screenshot = bitmap) }

    fun discardDraft() = _state.update { it.copy(draft = SupportDraft(), error = "") }

    /** [page] is the screen the report was started from, recorded in diagnostics. */
    fun submit(page: String, onSent: (SupportReport) -> Unit) {
        val draft = _state.value.draft
        if (_state.value.submitting) return
        if (draft.title.isBlank() || draft.body.isBlank()) {
            _state.update { it.copy(error = "Add a title and describe what happened.") }
            return
        }
        val attempt = generation.get()
        _state.update { it.copy(submitting = true, error = "") }
        scope.launch {
            try {
                val form = MultipartForm()
                    .field("kind", draft.kind)
                    .field("title", draft.title.trim())
                    .field("body", draft.body.trim())
                if (draft.diagnostics) form.field("diagnostics", supportDiagnostics(appContext, page).toString())
                draft.screenshot?.let { bitmap ->
                    val (bytes, type) = encodeScreenshot(bitmap)
                        ?: throw SupportHttpException(413, "too_large", "The screenshot is too large to send. Remove it and try again.")
                    form.file("screenshot", if (type == "image/png") "screenshot.png" else "screenshot.jpg", type, bytes)
                }
                val json = call { supportRequest(account.serviceUrl, "POST", "/support/reports", it, form.build(),
                    "multipart/form-data; boundary=${form.boundary}") }
                    ?: throw SupportHttpException(401, "invalid_token", "Sign in to your Orchard account first.")
                if (generation.get() != attempt) return@launch
                val report = SupportJson.report(json.getJSONObject("report"))
                _state.update { it.copy(reports = listOf(report) + it.reports, draft = SupportDraft(), active = report, events = emptyList()) }
                launch(Dispatchers.Main) { onSent(report) }
            } catch (e: Exception) {
                if (generation.get() != attempt) return@launch
                val unlinked = (e as? SupportHttpException)?.code == "github_required"
                _state.update { it.copy(error = failure(e), github = if (unlinked) null else it.github) }
            } finally {
                if (generation.get() == attempt) _state.update { it.copy(submitting = false) }
            }
        }
    }

    private fun clear() {
        generation.incrementAndGet()
        linkJob?.cancel()
        lastRefresh = 0
        // The draft survives; a sign-in hiccup should not eat a long bug report.
        _state.update { SupportState(draft = it.draft) }
    }

    companion object {
        const val POLL_INTERVAL_MS = 5 * 60_000L
        // Returning to the app refreshes, but not more than once a minute.
        const val RESUME_REFRESH_MS = 60_000L
        private const val LINK_POLL_MS = 4_000L
        private const val LINK_POLLS = 150
        @Volatile private var instance: SupportService? = null

        fun get(context: Context): SupportService = instance ?: synchronized(this) {
            instance ?: SupportService(context).also { instance = it }
        }
    }
}
