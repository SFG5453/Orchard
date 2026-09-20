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

package dev.sfg.orchard.mobile.playback

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.smart.CrossfadeMode
import dev.sfg.orchard.mobile.playback.smart.TrackAnalysis
import dev.sfg.orchard.mobile.playback.smart.TransitionPlan
import dev.sfg.orchard.mobile.playback.smart.TransitionFilter
import dev.sfg.orchard.mobile.playback.smart.TransitionPreparer
import dev.sfg.orchard.mobile.playback.smart.TransitionStyle
import dev.sfg.orchard.mobile.playback.smart.planTransition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The independent upper- and low-band gains for one instant of a live DJ blend. */
internal data class DjMixGains(
    val outgoingUpper: Double,
    val incomingUpper: Double,
    val outgoingBass: Double,
    val incomingBass: Double,
)

/**
 * Shapes the live two-player blend with bass fading smoothly alongside volume at equal power.
 *
 * Both the upper bands and the bass use complementary equal-power curves across the overlap.
 * Keeping this pure makes the audible invariants testable without constructing two ExoPlayers.
 */
internal fun djMixGains(progress: Double, fadeSeconds: Double = 0.0): DjMixGains {
    val position = progress.coerceIn(0.0, 1.0)
    val outgoing = cos(position * PI / 2.0)
    val incoming = sin(position * PI / 2.0)

    return DjMixGains(
        outgoingUpper = outgoing,
        incomingUpper = incoming,
        outgoingBass = outgoing,
        incomingBass = incoming,
    )
}

/** Incoming source position matching a point near the end of a rendered transition. */
internal fun renderedContinuationPositionMs(
    incomingResumeSeconds: Double,
    renderedRemainingMs: Long,
    incomingTempoRatio: Double,
): Long =
    ((incomingResumeSeconds * 1000.0) -
        renderedRemainingMs.coerceAtLeast(0) * incomingTempoRatio.coerceAtLeast(0.0))
        .toLong()
        .coerceAtLeast(0)

/**
 * True overlapping crossfade across a pair of ExoPlayers.
 *
 * ExoPlayer decodes one item at a time, so real overlap needs a second player. Near the end of a
 * track the standby player is loaded with the same queue positioned on the next item and started
 * silently; the two volumes then ramp against each other. At the end of the ramp the standby is
 * already playing the next track at the right position, so the handoff is a role swap rather than
 * a seek; nothing has to be re-buffered at the seam.
 *
 * *Where* and *how long* the overlap runs is not decided here: every tick asks
 * [planTransition] for a [TransitionPlan], and this class only executes it. In standard mode that
 * plan is the same trailing ramp it always was. In smart mode the plan is placed against stored
 * analysis: on a downbeat, past the incoming track's lead-in silence, ending where the music
 * actually does, and degrades back to the trailing ramp for any track it has no evidence about.
 *
 * The caller owns both players and is told, via [onHandoff], to move the media session onto the
 * incoming one.
 */
@UnstableApi
class CrossfadeEngine(
    private val handler: Handler,
    private val config: () -> Config,
    /**
     * Stored analysis for a track, or an empty [TrackAnalysis] when there is none. This is the seam
     * on-device analysis plugs into; until then every track comes back empty, which the policy
     * ladder reads as "no evidence" and answers with a plain fade.
     */
    private val analysisFor: (Track) -> TrackAnalysis = { TrackAnalysis() },
    /**
     * Reports the plan for the current track, or null when there is none to draw. Called on every
     * tick so the marker follows a re-plan; analysis finishing mid-track moves the transition.
     */
    private val onPlan: (TransitionPlan?) -> Unit = {},
    /**
     * A pre-rendered beat-matched overlap for this pair, or null. Never computes; the render
     * happens ahead of the seam, and by the time this is asked the answer is already on disk.
     */
    private val preparedFor: (outgoing: Track, incoming: Track) -> TransitionPreparer.Prepared? =
        { _, _ -> null },
    /**
     * The filters in each player's audio pipeline, outgoing first. Automating gain alone only makes
     * a track quieter; the filter ride is what makes a blend read as a mix rather than as two
     * records playing at once, and it has to happen inside the sink.
     */
    private val filters: () -> Pair<TransitionFilter, TransitionFilter>? = { null },
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
) {
    /** What the listener asked for, read fresh on every tick so a settings change lands at once. */
    data class Config(
        val enabled: Boolean,
        val fadeSeconds: Double,
        val mode: CrossfadeMode,
    )

    // Pair scoring belongs off the playback/UI thread. The shared planner caches musical choices;
    // subsequent ticks only schedule the cached result. Never queue work faster than it completes.
    private val planningExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "orchard-transition-plan").apply { isDaemon = true }
    }
    private var stagedRender: TransitionPreparer.Prepared? = null
    private var stagedPlaybackStarted = false
    private var renderedPlaying = false
    private data class RenderedPlayback(
        val prepared: TransitionPreparer.Prepared,
        val originalQueue: List<MediaItem>,
        val nextIndex: Int,
    )
    private var stagedRenderedPlayback: RenderedPlayback? = null
    private var activeRenderedPlayback: RenderedPlayback? = null
    private var continuationStaged = false
    private var continuationStarted = false
    private var renderedTailRunning = false
    private var planning = false
    private var planningGeneration = 0L

    private var active: ExoPlayer? = null
    private var standby: ExoPlayer? = null
    private var fading = false
    private var fadeStartedAt = 0L
    private var fadeWindowMs = 0L
    private var fadeStyle = TransitionStyle.EQUAL_POWER

    /** Begins watching [active] for the end of each track. Safe to call again to re-seat the pair. */
    fun start(active: ExoPlayer, standby: ExoPlayer) {
        planningGeneration++
        handler.removeCallbacks(renderedTail)
        renderedTailRunning = false
        this.active = active
        this.standby = standby
        fading = false
        stagedRender = null
        stagedRenderedPlayback = null
        activeRenderedPlayback = null
        stagedPlaybackStarted = false
        continuationStaged = false
        continuationStarted = false
        handler.removeCallbacks(watcher)
        handler.removeCallbacks(ramp)
        active.volume = 1f
        active.pauseAtEndOfMediaItems = false
        handler.post(watcher)
    }

    /**
     * Drops an in-flight fade and leaves the active player untouched. Anything that invalidates the
     * snapshot the standby was loaded with (a manual skip, a seek, a queue edit) must call this.
     */
    fun abort() {
        planningGeneration++
        handler.removeCallbacks(renderedTail)
        renderedTailRunning = false
        if ((stagedRender != null || activeRenderedPlayback != null) && !fading) {
            standby?.stop()
            standby?.clearMediaItems()
        }
        stagedRender = null
        stagedRenderedPlayback = null
        activeRenderedPlayback = null
        stagedPlaybackStarted = false
        continuationStaged = false
        continuationStarted = false
        if (!fading) return
        fading = false
        handler.removeCallbacks(ramp)
        filters()?.let { (outgoingFilter, incomingFilter) ->
            outgoingFilter.clearAutomation()
            incomingFilter.clearAutomation()
        }
        active?.let {
            it.volume = 1f
            it.pauseAtEndOfMediaItems = false
        }
        standby?.let {
            it.stop()
            it.clearMediaItems()
            it.volume = 1f
            it.setPlaybackParameters(PlaybackParameters.DEFAULT)
        }
    }

    fun release() {
        planningGeneration++
        planningExecutor.shutdownNow()
        handler.removeCallbacks(watcher)
        handler.removeCallbacks(ramp)
        handler.removeCallbacks(renderedTail)
        renderedTailRunning = false
        fading = false
        active = null
        standby = null
    }

    private val watcher = object : Runnable {
        override fun run() {
            handler.postDelayed(this, WATCH_INTERVAL_MS)
            if (fading) return
            val player = active ?: return
            // The temporary mix is already executing a fixed plan. Replanning it clears the
            // active marker and mistakes its short duration for a full outgoing song.
            if (player.isRenderedMix()) {
                scheduleRenderedTail()
                return
            }
            if (renderedPlaying) {
                renderedPlaying = false
                onPlan(null)
            }
            val settings = config()
            if (!settings.enabled || !player.isPlaying) {
                if (!settings.enabled) onPlan(null)
                return
            }
            // Repeating one track would fade it into itself.
            if (player.repeatMode == Player.REPEAT_MODE_ONE) return
            val duration = player.sourceDurationMs()
            if (duration == C.TIME_UNSET) return
            if (player.nextMediaItemIndex == C.INDEX_UNSET) return

            requestPlan(player, settings, duration)
        }
    }

    private fun executePlan(player: ExoPlayer, plan: TransitionPlan, duration: Long) {
        onPlan(plan.takeIf { it.markerVisible && !it.blocked })

        if (plan.blocked) return
        // Gapless playback across sequential album tracks is handled natively and seamlessly
        // by ExoPlayer within the active player. Beginning a multi-player handoff for gapless
        // would cause buffer stalls and stutter.
        if (plan.transitionStyle == TransitionStyle.GAPLESS) return
        // A smart plan can end before the file does, at an analyzed mix-out anchor. The ramp
        // has to close there, not at the end of the track.
        val endMs = (plan.transitionEnd * 1000).toLong().coerceAtMost(duration)

        // A rendered overlap replaces the ramp entirely rather than augmenting it: the mix is
        // already in the buffer, complete with its own fades, so ramping the players on top
        // would fade a finished mix in and out of itself.
        val prepared = currentPair(player)?.let { (out, into) -> preparedFor(out, into) }
        val sourceSeconds = player.sourcePositionMs() / 1000.0
        val matchingRender = prepared?.takeIf {
            it.selectedPlan == plan.nativePlan && plan.nativePlan != null
        }
        if (matchingRender != null && sourceSeconds < matchingRender.startSeconds) {
            stageRenderedTransition(matchingRender)
            if (sourceSeconds >= matchingRender.playbackStartSeconds) {
                primeRenderedTransition(matchingRender)
            }
        }
        if (matchingRender != null && sourceSeconds >= matchingRender.startSeconds &&
            sourceSeconds < matchingRender.endSeconds && beginRenderedTransition(matchingRender)) {
            Log.d(
                TAG,
                "Transition: rendered overlap, style=${plan.transitionStyle} " +
                    "beats=${plan.transitionBeats} stretch=${prepared.stretchRatio} " +
                    "out=${plan.transitionStart}..${plan.transitionEnd} " +
                    "in=${plan.incomingCueTime}->${plan.incomingHandoffTime} " +
                    "reason=${plan.reason}",
            )
            return
        }

        if (prepared != null && stagedRender != prepared && sourceSeconds >= prepared.startSeconds) {
            // The native window opened, but playback could not enter it. Do not animate a mix
            // that is not playing while waiting for the later live fallback window.
            onPlan(plan.copy(nativePlan = null))
        }
        if (!plan.shouldStart) return
        // Zero-duration desktop refusals must never become a minimum-length volume ramp.
        // Wait for the selected boundary, then advance with the exact incoming cue.
        if (plan.fadeSeconds <= 0) {
            if (player.sourcePositionMs() < endMs) return
            val next = player.nextMediaItemIndex
            if (next != C.INDEX_UNSET) player.seekTo(next, (plan.incomingCueTime * 1000).toLong())
            return
        }

        // The one line that says whether you heard the mix or the fallback. A render that was
        // planned but not ready is the interesting case: the plan asked for a beat-matched
        // blend and the ramp is what actually played.
        Log.d(
            TAG,
            "Transition: volume ramp, style=${plan.transitionStyle} " +
                "fadeMs=${plan.fadeMs} rate=${plan.incomingPlaybackRate} " +
                "renderReady=${prepared != null} " +
                "out=${plan.transitionStart}..${plan.transitionEnd} " +
                "in=${plan.incomingCueTime}->${plan.incomingHandoffTime} " +
                "reason=${plan.reason}",
        )
        // A prepared render can still be refused (for example a repeat-all queue wrap).
        // Freeze the marker to the live path before the watcher pauses during the fade.
        onPlan(plan.copy(nativePlan = null))
        beginFade(plan, remainingMs = endMs - player.sourcePositionMs())
    }

    /** The outgoing and incoming tracks of the transition about to happen. */
    private fun currentPair(player: ExoPlayer): Pair<Track, Track>? {
        val outgoing = player.currentMediaItem?.let(MediaItemMapper::toTrack) ?: return null
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return null
        return outgoing to MediaItemMapper.toTrack(player.getMediaItemAt(nextIndex))
    }

    /**
     * Hands playback to a pre-rendered overlap, then to the incoming track past its far edge.
     *
     * The standby player is given the whole queue with two slots rewritten: the outgoing track's
     * slot becomes the rendered mix, and the incoming track's becomes the rest of itself clipped to
     * resume where the mix left it. The clipped item is a fail-safe; ordinarily the freed player is
     * warmed on the original queue and takes over through a short equal-power splice before the WAV
     * reaches that decoder boundary.
     *
     * Rewriting in place rather than handing over a two-item playlist is what keeps the queue
     * intact. The standby player becomes authoritative at [finish], and the service persists it
     * from there, so anything missing from this playlist is not merely hidden for the length of the
     * transition — it is gone from the queue and from disk.
     *
     * The outgoing player is faded out over a few milliseconds rather than stopped dead. Its audio
     * and the start of the rendered buffer are the same material, but the buffer's copy has been
     * through a phase vocoder, so they are not phase-aligned; a hard cut there is a click and a
     * long crossfade is comb filtering. A very short fade is the one option that is neither.
     */
    private fun stageRenderedTransition(prepared: TransitionPreparer.Prepared): Boolean {
        if (stagedRender == prepared) return true
        val outgoing = active ?: return false
        val incoming = standby ?: return false
        val currentIndex = outgoing.currentMediaItemIndex
        val nextIndex = outgoing.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        // The rewrite below assumes the two slots are adjacent. A wrap under repeat-all, or any
        // other non-adjacent next, falls back to the volume ramp, which loads the queue whole.
        if (nextIndex != currentIndex + 1) return false
        if (!prepared.file.exists()) return false

        val queue = buildList<MediaItem> {
            for (index in 0 until outgoing.mediaItemCount) add(outgoing.getMediaItemAt(index))
        }

        // The mix opens with the outgoing track's own tail, so it keeps that track's identity:
        // media id, metadata and the track JSON the queue and persistence are rebuilt from. Only
        // the URI changes, and a file URI passes the stream resolver through untouched.
        val mix = queue[currentIndex].buildUpon()
            .setMediaMetadata(queue[currentIndex].mediaMetadata.buildUpon().setExtras(
                android.os.Bundle(queue[currentIndex].mediaMetadata.extras ?: android.os.Bundle()).apply {
                    putLong(MIX_SOURCE_START, (prepared.playbackStartSeconds * 1000).toLong())
                    putLong(MIX_SOURCE_DURATION, outgoing.sourceDurationMs())
                    putDouble(MIX_SOURCE_RATE, prepared.stretchRatio)
                }
            ).build())
            .setUri(android.net.Uri.fromFile(prepared.file))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.UNSET)
            .build()
        val remainder = queue[nextIndex].buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((prepared.incomingResumeSeconds * 1000).toLong().coerceAtLeast(0))
                    .build(),
            )
            .build()
        val playlist = spliceInPlace(queue, currentIndex, mix, remainder)

        incoming.pause()
        incoming.volume = 0f
        incoming.repeatMode = outgoing.repeatMode
        incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
        incoming.setPlaylistMetadata(outgoing.playlistMetadata)
        incoming.setMediaItems(playlist, currentIndex, 0L)
        incoming.setPlaybackParameters(PlaybackParameters.DEFAULT)
        incoming.prepare()
        stagedRender = prepared
        stagedRenderedPlayback = RenderedPlayback(prepared, queue, nextIndex)
        stagedPlaybackStarted = false
        return true
    }

    /** Starts the rendered player's silent lead-in while the live outgoing deck is still audible. */
    private fun primeRenderedTransition(prepared: TransitionPreparer.Prepared): Boolean {
        val outgoing = active ?: return false
        val incoming = standby ?: return false
        if (!stageRenderedTransition(prepared)) return false
        if (incoming.playerError != null || incoming.playbackState != Player.STATE_READY) return false
        if (stagedPlaybackStarted) return true

        val elapsedSource =
            (outgoing.sourcePositionMs() - (prepared.playbackStartSeconds * 1000).toLong())
                .coerceAtLeast(0)
        val rate = prepared.stretchRatio.coerceAtLeast(MIN_PLAYBACK_RATE)
        incoming.seekTo((elapsedSource / rate).toLong())
        incoming.volume = 0f
        incoming.play()
        stagedPlaybackStarted = true
        return true
    }

    private fun beginRenderedTransition(prepared: TransitionPreparer.Prepared): Boolean {
        val outgoing = active ?: return false
        val incoming = standby ?: return false
        if (!primeRenderedTransition(prepared)) return false
        if (incoming.playerError != null || incoming.playbackState != Player.STATE_READY || !incoming.isPlaying) {
            return false
        }

        // The silent lead-in makes decoder startup inaudible. If the standby clock did not stay
        // with the live deck, repair it while it is still muted instead of fading into stale PCM.
        val rate = prepared.stretchRatio.coerceAtLeast(MIN_PLAYBACK_RATE)
        val expectedPosition =
            ((outgoing.sourcePositionMs() - (prepared.playbackStartSeconds * 1000).toLong()) / rate)
                .toLong()
        if (abs(incoming.currentPosition - expectedPosition) > MAX_SPLICE_DRIFT_MS) {
            incoming.seekTo(expectedPosition.coerceAtLeast(0))
            return false
        }

        val rendered = stagedRenderedPlayback ?: return false
        incoming.volume = 0f
        fading = true
        renderedPlaying = true
        activeRenderedPlayback = rendered
        fadeStartedAt = SystemClock.elapsedRealtime()
        fadeWindowMs = SPLICE_FADE_MS
        fadeStyle = TransitionStyle.EQUAL_POWER
        outgoing.pauseAtEndOfMediaItems = true
        handler.post(ramp)
        return true
    }

    /**
     * Prepares the real incoming item on the now-free deck while the rendered WAV is playing.
     * Keeping this decoder hot avoids the format switch (PCM WAV to the original stream) that used
     * to create the second audible hole at the far edge of every rendered transition.
     */
    private fun stageRenderedContinuation(rendered: RenderedPlayback): Boolean {
        if (continuationStaged) return true
        val outgoing = active ?: return false
        val incoming = standby ?: return false
        if (rendered.nextIndex !in rendered.originalQueue.indices) return false

        if (rendered.prepared.selectedPlan == null) return false
        val incomingRate = rendered.prepared.incomingStretchRatio.coerceAtLeast(MIN_PLAYBACK_RATE)
        val prerollMs = minOf(
            CONTINUATION_PREROLL_MS,
            (rendered.prepared.renderedDurationSeconds * 1000).toLong(),
        )
        val startPosition = renderedContinuationPositionMs(
            rendered.prepared.incomingResumeSeconds,
            prerollMs,
            incomingRate,
        )

        incoming.pause()
        incoming.volume = 0f
        incoming.pauseAtEndOfMediaItems = false
        incoming.repeatMode = outgoing.repeatMode
        incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
        incoming.setPlaylistMetadata(outgoing.playlistMetadata)
        incoming.setMediaItems(rendered.originalQueue, rendered.nextIndex, startPosition)
        incoming.setPlaybackParameters(
            PlaybackParameters(incomingRate.toFloat()),
        )
        incoming.prepare()
        continuationStaged = true
        continuationStarted = false
        return true
    }

    private fun scheduleRenderedTail() {
        if (activeRenderedPlayback == null || renderedTailRunning) return
        renderedTailRunning = true
        handler.post(renderedTail)
    }

    private val renderedTail = object : Runnable {
        override fun run() {
            val rendered = activeRenderedPlayback
            val outgoing = active
            val incoming = standby
            if (rendered == null || outgoing == null || incoming == null) {
                renderedTailRunning = false
                return
            }
            if (!outgoing.isRenderedMix()) {
                // The fail-safe playlist has already advanced into its clipped remainder. It is
                // better to leave that authoritative than to swap to a standby that missed sync.
                activeRenderedPlayback = null
                if (continuationStaged) {
                    incoming.stop()
                    incoming.clearMediaItems()
                }
                continuationStaged = false
                continuationStarted = false
                renderedTailRunning = false
                return
            }
            if (fading) {
                handler.postDelayed(this, RAMP_INTERVAL_MS)
                return
            }
            if (!stageRenderedContinuation(rendered)) {
                handler.postDelayed(this, RAMP_INTERVAL_MS)
                return
            }
            if (incoming.playerError != null) {
                renderedTailRunning = false
                return
            }

            val durationMs = (rendered.prepared.outputDurationSeconds * 1000).toLong()
            val remainingMs = (durationMs - outgoing.currentPosition).coerceAtLeast(0)
            if (rendered.prepared.selectedPlan == null) {
                renderedTailRunning = false
                return
            }
            val incomingRate = rendered.prepared.incomingStretchRatio.coerceAtLeast(MIN_PLAYBACK_RATE)
            if (!continuationStarted && remainingMs <= CONTINUATION_PREROLL_MS &&
                incoming.playbackState == Player.STATE_READY) {
                incoming.seekTo(
                    renderedContinuationPositionMs(
                        rendered.prepared.incomingResumeSeconds,
                        remainingMs,
                        incomingRate,
                    ),
                )
                incoming.play()
                continuationStarted = true
            }

            if (continuationStarted && incoming.playbackState == Player.STATE_READY && incoming.isPlaying) {
                val expectedPosition = renderedContinuationPositionMs(
                    rendered.prepared.incomingResumeSeconds,
                    remainingMs,
                    incomingRate,
                )
                val drift = abs(incoming.currentPosition - expectedPosition)
                if (remainingMs > SPLICE_FADE_MS * 2 && drift > MAX_SPLICE_DRIFT_MS) {
                    // Still muted and comfortably ahead of the seam: repair startup skew now.
                    incoming.seekTo(expectedPosition)
                } else if (remainingMs <= SPLICE_FADE_MS + RAMP_INTERVAL_MS &&
                    drift <= MAX_SPLICE_DRIFT_MS) {
                    fading = true
                    fadeStartedAt = SystemClock.elapsedRealtime()
                    fadeWindowMs = min(SPLICE_FADE_MS, remainingMs).coerceAtLeast(MIN_RAMP_MS)
                    fadeStyle = TransitionStyle.EQUAL_POWER
                    handler.post(ramp)
                    return
                }
            }
            handler.postDelayed(this, RAMP_INTERVAL_MS)
        }
    }

    private fun requestPlan(player: ExoPlayer, settings: Config, durationMs: Long) {
        if (planning) return
        val currentTrack = player.currentMediaItem?.let(MediaItemMapper::toTrack) ?: return
        val nextTrack = player.getMediaItemAt(player.nextMediaItemIndex).let(MediaItemMapper::toTrack)
        val analysis = analysisFor(currentTrack)
        val nextAnalysis = analysisFor(nextTrack)
        val position = player.sourcePositionMs()
        val albumSequential = isAlbumPlaythrough(player, currentTrack)
        val generation = planningGeneration
        planning = true
        planningExecutor.execute {
            val result = runCatching {
                planTransition(analysis, nextAnalysis, currentTrack, nextTrack,
                    currentTime = position / 1000.0, duration = durationMs / 1000.0,
                    fadeSeconds = settings.fadeSeconds, mode = settings.mode, albumSequential = albumSequential)
            }
            handler.post {
                planning = false
                if (generation != planningGeneration || active !== player || fading || !player.isPlaying ||
                    config() != settings || currentPair(player) != (currentTrack to nextTrack) ||
                    analysisFor(currentTrack) != analysis || analysisFor(nextTrack) != nextAnalysis ||
                    isAlbumPlaythrough(player, currentTrack) != albumSequential ||
                    kotlin.math.abs(player.sourcePositionMs() - position) > WATCH_INTERVAL_MS * 2) return@post
                result.onSuccess { executePlan(player, it, durationMs) }
                    .onFailure { Log.w(TAG, "Desktop transition planning failed", it) }
            }
        }
    }

    /**
     * Whether this queue is an album genuinely being played through in order; the only case that
     * earns a gapless handoff instead of a mix.
     *
     * Shuffle and any non-adjacent next item rule it out. Beyond that the queue's own context title
     * has to name the album: two album siblings that happen to land next to each other inside a
     * playlist are still a mix, and mixing them is what the listener asked for.
     *
     * This is a proxy, not proof. `OrchardViewModel.playAll` falls back to the starting track's own
     * album when a caller supplies no context, so a queue that was never an album can still carry an
     * album's name. What survives that is narrow (an unshuffled queue, adjacent items, a shared
     * album, and a context naming it), and joining those two gaplessly is the likely intent anyway.
     * A real queue-origin field on the track would settle it properly.
     */
    internal fun isAlbumPlaythrough(player: ExoPlayer, currentTrack: Track?): Boolean {
        if (player.shuffleModeEnabled) return false
        if (player.nextMediaItemIndex != player.currentMediaItemIndex + 1) return false
        val album = currentTrack?.album?.takeIf { it.isNotBlank() } ?: return false
        val context = player.playlistMetadata.title?.toString().orEmpty()
        if (context.endsWith("• Best Mix", ignoreCase = true) || context.equals("Best Mix", ignoreCase = true)) return false
        return context.equals(album, ignoreCase = true)
    }

    private fun beginFade(plan: TransitionPlan, remainingMs: Long) {
        val outgoing = active ?: return
        val incoming = standby ?: return
        val nextIndex = outgoing.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val queue = buildList<MediaItem> {
            for (index in 0 until outgoing.mediaItemCount) add(outgoing.getMediaItemAt(index))
        }
        // A fade aborted mid-window, or a plan placed against a slightly stale duration, can leave
        // less track than the plan asked for. Ramping over what is actually left keeps the two
        // halves aligned rather than cutting the outgoing track off part-way down.
        fadeWindowMs = min(plan.fadeMs, remainingMs).coerceAtLeast(MIN_RAMP_MS)
        fading = true
        fadeStartedAt = SystemClock.elapsedRealtime()
        fadeStyle = plan.transitionStyle
        // The incoming player owns what plays next, so the outgoing one must not advance on its own.
        outgoing.pauseAtEndOfMediaItems = true
        incoming.volume = if (plan.transitionStyle == TransitionStyle.GAPLESS) 1f else 0f
        incoming.repeatMode = outgoing.repeatMode
        incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
        incoming.setPlaylistMetadata(outgoing.playlistMetadata)
        // Smart plans cue past the incoming track's lead-in silence, or back from its drop so the
        // arrangement lands where the outgoing track ends. Standard plans always cue at zero.
        incoming.setMediaItems(queue, nextIndex, (plan.incomingCueTime * 1000).toLong())
        // Media3 time-stretches without shifting pitch, so a tempo nudge inside the transparent
        // window is a beat-match rather than a detune. Plans that earned no nudge return 1.0.
        incoming.setPlaybackParameters(PlaybackParameters(plan.incomingPlaybackRate.toFloat()))
        if (fadeStyle == TransitionStyle.DJ_BLEND || fadeStyle == TransitionStyle.DJ_FILTER) {
            val initialGains = djMixGains(0.0, fadeWindowMs / 1000.0)
            automateFilters(0f, initialGains)
        }
        incoming.prepare()
        incoming.play()
        handler.post(ramp)
    }

    private val ramp = object : Runnable {
        override fun run() {
            val outgoing = active ?: return
            val incoming = standby ?: return
            // Fading into a stream that failed to load would just be a fade to silence.
            if (incoming.playerError != null) {
                abort()
                return
            }
            val progress = ((SystemClock.elapsedRealtime() - fadeStartedAt).toFloat() / fadeWindowMs)
                .coerceIn(0f, 1f)
            if (fadeStyle == TransitionStyle.GAPLESS) {
                automateFilters(progress)
                // Not a blend: the incoming track is already at full volume and the outgoing one
                // just gets out of the way, so the seam stays as tight as the decoder allows.
                outgoing.volume = 1f - progress
            } else if (fadeStyle == TransitionStyle.DJ_BLEND || fadeStyle == TransitionStyle.DJ_FILTER) {
                val gains = djMixGains(progress.toDouble(), fadeWindowMs / 1000.0)
                automateFilters(progress, gains)
                outgoing.volume = gains.outgoingUpper.toFloat()
                incoming.volume = gains.incomingUpper.toFloat()
            } else {
                automateFilters(progress)
                // Equal power: ramping both volumes linearly dips the perceived loudness mid-fade.
                outgoing.volume = cos(progress * PI.toFloat() / 2f)
                incoming.volume = sin(progress * PI.toFloat() / 2f)
            }
            if (progress < 1f) {
                handler.postDelayed(this, RAMP_INTERVAL_MS)
                return
            }
            finish(outgoing, incoming)
        }
    }

    /**
     * Rides the filters across the overlap, which is what the `dj_assisted` tier buys.
     *
     * Three moves, all on the outgoing channel except the bass handover.
     *
     * The low-pass sweeps down from above hearing, so the first part of the ride is inaudible and
     * the transition does not announce itself. It takes the top away first and the mids last, so
     * the outgoing track thins out and recedes instead of merely getting quieter, and because the
     * corner is moving, the ear follows the movement, which is what covers the seam.
     *
     * The outgoing channel receives mid-frequency ducking (up to -6 dB) scaled by the incoming track's
     * power to prevent spectral collision where both tracks are loudest.
     *
     * The low end changes hands in a short equal-power ramp near the end. Its target gains are
     * independent of the upper fade, so the outgoing kick keeps full weight through the runway and
     * the incoming kick does not arrive as a step.
     *
     * A plain fade gets none of this: a transition the policy would not trust to beat-match is
     * still a transition, but filtering one of two arbitrary tracks is a colour, not a mix.
     */
    private fun automateFilters(progress: Float, gains: DjMixGains? = null) {
        val (outgoingFilter, incomingFilter) = filters() ?: return
        if (fadeStyle == TransitionStyle.EQUAL_POWER || fadeStyle == TransitionStyle.GAPLESS) {
            outgoingFilter.clearAutomation()
            incomingFilter.clearAutomation()
            return
        }

        // Exponential in frequency, because pitch is logarithmic: a linear sweep spends most of its
        // travel in the top octave where there is nothing to hear.
        val span = TransitionFilter.SWEEP_START_HZ / TransitionFilter.BASS_CROSSOVER_HZ
        val depth = SWEEP_DEPTH * progress
        outgoingFilter.lowPassHz =
            TransitionFilter.SWEEP_START_HZ / span.pow(depth.toDouble())

        // Mid-ducking on the outgoing channel: duck by up to -6 dB as incoming arrives to prevent
        // mid-band collision and spectral summing.
        val fadeIn = sin(progress * (PI.toFloat() / 2f))
        val midDuckDb = -6.0 * (fadeIn * fadeIn)
        outgoingFilter.gain = 10.0.pow(midDuckDb / 20.0)

        val mixGains = gains ?: djMixGains(progress.toDouble(), fadeWindowMs / 1000.0)
        outgoingFilter.bassGain = mixGains.outgoingBass
        incomingFilter.bassGain = mixGains.incomingBass
        incomingFilter.lowPassHz = TransitionFilter.OPEN
        incomingFilter.gain = 1.0
    }

    private fun finish(outgoing: ExoPlayer, incoming: ExoPlayer) {
        val enteringRenderedPlayback = incoming.isRenderedMix()
        val leavingRenderedPlayback = activeRenderedPlayback != null && !enteringRenderedPlayback
        incoming.volume = 1f
        incoming.pauseAtEndOfMediaItems = false
        // The nudge only ever existed to align the two grids through the overlap.
        incoming.setPlaybackParameters(PlaybackParameters.DEFAULT)
        // Cleared before the roles swap, so neither filter is left holding a sweep from a
        // transition that has ended.
        filters()?.let { (outgoingFilter, incomingFilter) ->
            outgoingFilter.clearAutomation()
            incomingFilter.clearAutomation()
        }
        stagedRender = null
        stagedRenderedPlayback = null
        stagedPlaybackStarted = false
        if (leavingRenderedPlayback) {
            activeRenderedPlayback = null
            continuationStaged = false
            continuationStarted = false
            handler.removeCallbacks(renderedTail)
            renderedTailRunning = false
        }
        active = incoming
        standby = outgoing
        fading = false
        onHandoff(outgoing, incoming)
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f
        outgoing.pauseAtEndOfMediaItems = false
        if (enteringRenderedPlayback) scheduleRenderedTail()
    }

    companion object {
        /**
         * The queue a rendered transition plays from: the same queue, with the outgoing and
         * incoming slots rewritten in place.
         *
         * Separated out and kept total because the size and offsets are the whole point. The
         * standby player becomes authoritative the moment the transition finishes and the service
         * persists it from there, so a playlist that is short by one is a queue the listener has
         * permanently lost the tail of — which is what a two-item playlist here used to do.
         */
        internal fun <T> spliceInPlace(queue: List<T>, currentIndex: Int, mix: T, remainder: T): List<T> {
            val nextIndex = currentIndex + 1
            require(currentIndex >= 0 && nextIndex <= queue.lastIndex) {
                "splice needs an adjacent pair inside the queue, got $currentIndex of ${queue.size}"
            }
            return queue.take(currentIndex) + mix + remainder + queue.drop(nextIndex + 1)
        }

        private const val TAG = "OrchardCrossfade"

        private const val WATCH_INTERVAL_MS = 200L
        private const val RAMP_INTERVAL_MS = 20L

        /** One ramp tick. Below this a fade is a cut, not a ramp. */
        private const val MIN_RAMP_MS = 40L

        /**
         * How long the live outgoing track takes to give way to the rendered buffer. Short enough
         * that two phase-divergent copies of the same audio never overlap audibly, long enough that
         * the cut is not a click.
         */
        private const val SPLICE_FADE_MS = 120L

        /** Starts the original incoming decoder early, muted, so its first audible sample is hot. */
        private const val CONTINUATION_PREROLL_MS = 1_500L

        /** Maximum clock skew tolerated while crossfading two copies of the incoming track. */
        private const val MAX_SPLICE_DRIFT_MS = 120L

        private const val MIN_PLAYBACK_RATE = 0.01

        /**
         * How far the low-pass travels toward the bass crossover by the end of the overlap. Short of
         * 1.0 on purpose: closing all the way onto the bass band leaves the outgoing track as a
         * rumble, which reads as a fault rather than as a mix.
         */
        private const val SWEEP_DEPTH = 0.85f

    }
}
