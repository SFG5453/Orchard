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
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.songlinks.LinkResolution
import dev.sfg.orchard.mobile.songlinks.SongLinksCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Debounced catalog search; pasted song links resolve to their tracks or open their collection. */
internal class SearchController(
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val songLinks: SongLinksCoordinator,
    private val openDetail: (String) -> Unit,
) {
    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()
    private val mutableResults = MutableStateFlow<LoadState<SearchResults>>(LoadState.Idle)
    val results: StateFlow<LoadState<SearchResults>> = mutableResults.asStateFlow()

    fun update(value: String) {
        mutableQuery.value = value
    }

    fun run(value: String) {
        mutableQuery.value = value.trim()
        graph.settings.recordSearch(value)
    }

    @OptIn(FlowPreview::class)
    fun observe() {
        scope.launch {
            mutableQuery.debounce(350).distinctUntilChanged().collectLatest { value ->
                if (value.isBlank()) {
                    mutableResults.value = LoadState.Idle
                    return@collectLatest
                }
                mutableResults.value = LoadState.Loading

                if (graph.songLinks.parseLink(value) != null) {
                    when (val res = songLinks.resolveLink(value)) {
                        is LinkResolution.PlayTrack -> {
                            mutableResults.value = LoadState.Content(SearchResults(tracks = listOf(res.track)))
                            return@collectLatest
                        }
                        is LinkResolution.OpenCollection -> openDetail(res.browseId)
                        null -> Unit
                    }
                }

                mutableResults.value = runCatching { graph.catalog.search(value) }
                    .fold(
                        onSuccess = { if (it.isEmpty) LoadState.Empty("No music matched “$value”.") else LoadState.Content(it) },
                        onFailure = { LoadState.Error(it.message ?: "Search is unavailable.") },
                    )
            }
        }
    }
}
