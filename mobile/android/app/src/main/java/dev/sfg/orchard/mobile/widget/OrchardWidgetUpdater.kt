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

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.playback.MediaItemMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coalesces player events into one durable state write and one widget render pass. */
object OrchardWidgetUpdater {
    private val renderMutex = Mutex()

    fun onPlayerChanged(context: Context, player: Player, forcePaused: Boolean = false) {
        val appContext = context.applicationContext
        val graph = OrchardGraph.from(appContext)
        val current = player.currentMediaItem?.let(MediaItemMapper::toTrack)
        val duration = player.duration
            .takeUnless { it == C.TIME_UNSET }
            ?.coerceAtLeast(0)
            ?: current?.durationMs
            ?: 0
        val isPlaying = player.isPlaying && !forcePaused
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val recentlyPlayed = graph.library.library.value.recentlyPlayed.take(4)
        val state = OrchardWidgetState(
            currentTrack = current,
            recentlyPlayed = recentlyPlayed,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = duration,
        )
        graph.applicationScope.launch(Dispatchers.IO) {
            OrchardWidgetStateStore(appContext).save(state)
            renderMutex.withLock {
                OrchardWidgetRenderer.updateAll(
                    context = appContext,
                    state = state,
                    client = graph.http,
                )
            }
        }
    }

    fun requestRender(context: Context) {
        val appContext = context.applicationContext
        val graph = OrchardGraph.from(appContext)
        graph.applicationScope.launch(Dispatchers.IO) {
            renderMutex.withLock {
                OrchardWidgetRenderer.updateAll(
                    context = appContext,
                    state = OrchardWidgetStateStore(appContext).load(),
                    client = graph.http,
                )
            }
        }
    }
}
