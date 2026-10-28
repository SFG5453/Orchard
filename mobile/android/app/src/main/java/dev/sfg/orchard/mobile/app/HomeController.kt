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

package dev.sfg.orchard.mobile.app

import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.download.DownloadStatus
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.LoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Home shelves from [OrchardGraph.homeFeeds], reloaded when the chosen source or connectivity changes. */
internal class HomeController(
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val showWarning: (String) -> Unit,
) {
    private val mutableHome = MutableStateFlow<LoadState<List<CatalogSection>>>(LoadState.Loading)
    val home: StateFlow<LoadState<List<CatalogSection>>> = mutableHome.asStateFlow()
    private var loadJob: Job? = null

    fun observe() {
        scope.launch {
            graph.networkMonitor.isOnline.collect { online ->
                if (online && mutableHome.value is LoadState.Error) refresh()
            }
        }
        scope.launch {
            graph.settings.loadedSettings.map { it.homeSource }.distinctUntilChanged().drop(1).collect { refresh() }
        }
        scope.launch {
            graph.appleMusic.connection.map { it.connected }.distinctUntilChanged().drop(1).collect {
                if (graph.settings.settings.value.homeSource == "apple_music") refresh()
            }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            mutableHome.value = LoadState.Loading
            // The settings StateFlow starts at defaults (YouTube Music); read the stored choice.
            val source = graph.settings.loadedSettings.first().homeSource
            mutableHome.value = runCatching { graph.homeFeeds.load(source) }.fold(
                onSuccess = { result ->
                    result.warning?.let(showWarning)
                    val sections = result.feed.sections
                    if (sections.isEmpty()) LoadState.Empty("No recommendations are available yet.") else LoadState.Content(sections)
                },
                onFailure = { if (it is CancellationException) throw it else offlineState(it) },
            )
        }
    }

    /** "See all" for a Home shelf, served by the source that filled Home. */
    suspend fun sectionItems(browseId: String, params: String): List<CatalogItem> {
        if (browseId.isBlank()) return emptyList()
        return runCatching { graph.homeFeeds.sectionItems(browseId, params) }.getOrDefault(emptyList())
    }

    private fun offlineState(error: Throwable): LoadState<List<CatalogSection>> {
        val hasDownloads = graph.downloads.downloads.value.values.any { it.status == DownloadStatus.COMPLETED }
        return if (!graph.networkMonitor.isOnline.value &&
            (hasDownloads || graph.library.library.value.recentlyPlayed.isNotEmpty())) {
            LoadState.Error("Orchard is offline. Your downloaded music is available.", true)
        } else {
            LoadState.Error(error.message ?: "Home could not be loaded.")
        }
    }
}
