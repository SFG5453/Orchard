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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.smart.CrossfadeMode
import dev.sfg.orchard.mobile.playback.smart.TrackAnalysis
import dev.sfg.orchard.mobile.playback.smart.TransitionChoreography
import dev.sfg.orchard.mobile.playback.smart.TransitionPlan
import dev.sfg.orchard.mobile.playback.smart.TransitionFilter
import dev.sfg.orchard.mobile.playback.smart.TransitionStyle
import dev.sfg.orchard.mobile.playback.smart.WsolaPlanResult
import dev.sfg.orchard.mobile.playback.smart.evaluateAutomationCurve
import dev.sfg.orchard.mobile.playback.smart.forLivePlayback
import dev.sfg.orchard.mobile.playback.smart.planTransition
import kotlin.math.PI
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
    private var planning = false
    private var planningGeneration = 0L

    private var active: ExoPlayer? = null
    private var standby: ExoPlayer? = null
    private var fading = false
    private var fadeStartedAt = 0L
    private var fadeWindowMs = 0L
    private var fadeStyle = TransitionStyle.EQUAL_POWER
    private var fadeInitialProgress = 0.0
    private var fadeChoreography: TransitionChoreography? = null
    private data class StagedLiveTransition(
        val selectedPlan: WsolaPlanResult.Planned,
        val queueIds: List<String>,
        val nextIndex: Int,
        var primed: Boolean = false,
    )
    private var stagedLiveTransition: StagedLiveTransition? = null

    /** Begins watching [active] for the end of each track. Safe to call again to re-seat the pair. */
    fun start(active: ExoPlayer, standby: ExoPlayer) {
        planningGeneration++
        this.active = active
        this.standby = standby
        fading = false
        fadeInitialProgress = 0.0
        fadeChoreography = null
        stagedLiveTransition = null
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
        val hadStagedTransition = stagedLiveTransition != null
        stagedLiveTransition = null
        if (!fading && !hadStagedTransition) return
        fading = false
        fadeInitialProgress = 0.0
        fadeChoreography = null
        handler.removeCallbacks(ramp)
        filters()?.let { (outgoingFilter, incomingFilter) ->
            outgoingFilter.clearAutomation()
            incomingFilter.clearAutomation()
        }
        active?.let {
            it.volume = 1f
            it.pauseAtEndOfMediaItems = false
            it.setPlaybackParameters(PlaybackParameters.DEFAULT)
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
        fading = false
        stagedLiveTransition = null
        active = null
        standby = null
    }

    private val watcher = object : Runnable {
        override fun run() {
            handler.postDelayed(this, WATCH_INTERVAL_MS)
            if (fading) return
            val player = active ?: return
            val settings = config()
            if (!settings.enabled || !player.isPlaying) {
                discardStagedLiveTransition()
                if (!settings.enabled) onPlan(null)
                return
            }
            // Either side of a video transition needs the session's visible surface. Keep both
            // sources on the session player instead of starting the audio-only standby deck.
            val nextIndex = player.nextMediaItemIndex
            if (MediaItemMapper.isVideoUri(player.currentMediaItem?.localConfiguration?.uri) ||
                (nextIndex != C.INDEX_UNSET &&
                    MediaItemMapper.isVideoUri(player.getMediaItemAt(nextIndex).localConfiguration?.uri))
            ) {
                discardStagedLiveTransition()
                onPlan(null)
                return
            }
            // Repeating one track would fade it into itself.
            if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                discardStagedLiveTransition()
                return
            }
            val duration = player.sourceDurationMs()
            if (duration == C.TIME_UNSET || player.nextMediaItemIndex == C.INDEX_UNSET) {
                discardStagedLiveTransition()
                return
            }

            requestPlan(player, settings, duration)
        }
    }

    private fun executePlan(player: ExoPlayer, plan: TransitionPlan, duration: Long) {
        // The shared planner still owns the selected cues, rates, duration, curves, and fallback.
        // Mobile executes that selection directly through its two live players instead of turning
        // it into a temporary WAV and crossing two decoder boundaries around it.
        val execution = plan.forLivePlayback(player.sourcePositionMs() / 1000.0)
        onPlan(execution.takeIf { it.markerVisible && !it.blocked })

        if (execution.blocked) {
            discardStagedLiveTransition()
            return
        }
        // Gapless playback across sequential album tracks is handled natively and seamlessly
        // by ExoPlayer within the active player. Beginning a multi-player handoff for gapless
        // would cause buffer stalls and stutter.
        if (execution.transitionStyle == TransitionStyle.GAPLESS) {
            discardStagedLiveTransition()
            return
        }
        // A smart plan can end before the file does, at an analyzed mix-out anchor. The ramp
        // has to close there, not at the end of the track.
        val endMs = (execution.transitionEnd * 1000).toLong().coerceAtMost(duration)
        if (!execution.shouldStart) {
            if (execution.nativePlan != null) {
                stageLiveTransition(execution)
                primeLiveTransition(execution, player.sourcePositionMs() / 1000.0)
            } else {
                discardStagedLiveTransition()
            }
            return
        }
        if (execution.nativePlan == null) discardStagedLiveTransition()
        // Zero-duration desktop refusals must never become a minimum-length volume ramp.
        // Wait for the selected boundary, then advance with the exact incoming cue.
        if (execution.fadeSeconds <= 0) {
            if (player.sourcePositionMs() < endMs) return
            val next = player.nextMediaItemIndex
            if (next != C.INDEX_UNSET) player.seekTo(next, (execution.incomingCueTime * 1000).toLong())
            return
        }

        Log.d(
            TAG,
            "Transition: live shared plan, style=${execution.transitionStyle} " +
                "fadeMs=${execution.fadeMs} rates=${execution.outgoingPlaybackRate}/" +
                "${execution.incomingPlaybackRate} " +
                "out=${execution.transitionStart}..${execution.transitionEnd} " +
                "in=${execution.incomingCueTime}->${execution.incomingHandoffTime} " +
                "reason=${execution.reason}",
        )
        beginFade(execution, remainingMs = endMs - player.sourcePositionMs())
    }

    /**
     * Buffers the selected incoming source before its first audible sample is needed.
     *
     * This is deliberately still the original media item, not an intermediate mix file. Preparing
     * it early keeps network, extractor, and decoder startup out of the transition window.
     */
    private fun stageLiveTransition(plan: TransitionPlan): Boolean {
        val selected = plan.nativePlan ?: return false
        val outgoing = active ?: return false
        val incoming = standby ?: return false
        val nextIndex = outgoing.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val queue = buildList {
            for (index in 0 until outgoing.mediaItemCount) add(outgoing.getMediaItemAt(index))
        }
        val queueIds = queue.map { it.mediaId }
        val staged = stagedLiveTransition
        if (staged?.selectedPlan == selected && staged.queueIds == queueIds &&
            staged.nextIndex == nextIndex && incoming.playerError == null) {
            incoming.repeatMode = outgoing.repeatMode
            incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
            incoming.setPlaylistMetadata(outgoing.playlistMetadata)
            return true
        }

        incoming.pause()
        incoming.volume = 0f
        incoming.repeatMode = outgoing.repeatMode
        incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
        incoming.setPlaylistMetadata(outgoing.playlistMetadata)
        incoming.setMediaItems(queue, nextIndex, (selected.incomingCueTime * 1000).toLong())
        incoming.setPlaybackParameters(
            PlaybackParameters(selected.incomingTempoRatio.coerceAtLeast(MIN_PLAYBACK_RATE).toFloat()),
        )
        incoming.prepare()
        stagedLiveTransition = StagedLiveTransition(selected, queueIds, nextIndex)
        return true
    }

    /** Starts the buffered incoming player muted so its AudioTrack is hot at the mix boundary. */
    private fun primeLiveTransition(plan: TransitionPlan, currentTime: Double) {
        val selected = plan.nativePlan ?: return
        val incoming = standby ?: return
        val staged = stagedLiveTransition ?: return
        if (staged.selectedPlan != selected || staged.primed ||
            incoming.playerError != null || incoming.playbackState != Player.STATE_READY) return

        val incomingRate = selected.incomingTempoRatio.coerceAtLeast(MIN_PLAYBACK_RATE)
        val availablePreroll = selected.incomingCueTime.coerceAtLeast(0.0) / incomingRate
        val preroll = min(LIVE_PREROLL_SECONDS, availablePreroll)
        val remaining = selected.transitionStart - currentTime
        if (remaining > preroll) return

        val startPosition =
            (selected.incomingCueTime - remaining.coerceAtLeast(0.0) * incomingRate)
                .coerceAtLeast(0.0)
        incoming.seekTo((startPosition * 1000).toLong())
        incoming.volume = 0f
        incoming.play()
        staged.primed = true
    }

    private fun discardStagedLiveTransition() {
        if (stagedLiveTransition == null) return
        stagedLiveTransition = null
        standby?.let {
            it.stop()
            it.clearMediaItems()
            it.volume = 1f
            it.setPlaybackParameters(PlaybackParameters.DEFAULT)
        }
    }

    /** The outgoing and incoming tracks of the transition about to happen. */
    private fun currentPair(player: ExoPlayer): Pair<Track, Track>? {
        val outgoing = player.currentMediaItem?.let(MediaItemMapper::toTrack) ?: return null
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return null
        return outgoing to MediaItemMapper.toTrack(player.getMediaItemAt(nextIndex))
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
        val queue = buildList {
            for (index in 0 until outgoing.mediaItemCount) add(outgoing.getMediaItemAt(index))
        }
        // A fade aborted mid-window, or a plan placed against a slightly stale duration, can leave
        // less track than the plan asked for. Ramping over what is actually left keeps the two
        // halves aligned rather than cutting the outgoing track off part-way down.
        val outgoingRate = plan.outgoingPlaybackRate.coerceAtLeast(MIN_PLAYBACK_RATE)
        val remainingWallMs = (remainingMs.coerceAtLeast(0) / outgoingRate).toLong()
        fadeWindowMs = min(plan.fadeMs, remainingWallMs).coerceAtLeast(MIN_RAMP_MS)
        fading = true
        fadeStartedAt = SystemClock.elapsedRealtime()
        fadeStyle = plan.transitionStyle
        fadeInitialProgress = plan.initialProgress.coerceIn(0.0, 1.0)
        fadeChoreography = plan.choreography?.takeIf { it.validate().isValid }
        // The incoming player owns what plays next, so the outgoing one must not advance on its own.
        outgoing.pauseAtEndOfMediaItems = true
        outgoing.setPlaybackParameters(PlaybackParameters(outgoingRate.toFloat()))
        val staged = stagedLiveTransition
        val reusesStagedPlayer = staged != null && staged.selectedPlan == plan.nativePlan &&
            staged.queueIds == queue.map { it.mediaId } && staged.nextIndex == nextIndex &&
            incoming.playerError == null
        stagedLiveTransition = null
        incoming.volume = 0f
        if (!reusesStagedPlayer) {
            incoming.repeatMode = outgoing.repeatMode
            incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
            incoming.setPlaylistMetadata(outgoing.playlistMetadata)
            // Smart plans cue past the incoming track's lead-in silence, or back from its drop so
            // the arrangement lands where the outgoing track ends. Standard plans cue at zero.
            incoming.setMediaItems(queue, nextIndex, (plan.incomingCueTime * 1000).toLong())
            incoming.prepare()
        } else {
            val desiredPositionMs = (plan.incomingCueTime * 1000).toLong()
            if (kotlin.math.abs(incoming.currentPosition - desiredPositionMs) > MAX_PREROLL_DRIFT_MS) {
                // Still inaudible here. Repair a late planner tick before applying its first gain.
                incoming.seekTo(desiredPositionMs)
            }
        }
        // Media3 time-stretches without shifting pitch, so a tempo nudge inside the transparent
        // window is a beat-match rather than a detune. Plans that earned no nudge return 1.0.
        incoming.setPlaybackParameters(PlaybackParameters(plan.incomingPlaybackRate.toFloat()))
        applyMixState(outgoing, incoming, fadeInitialProgress.toFloat())
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
            val elapsedProgress =
                ((SystemClock.elapsedRealtime() - fadeStartedAt).toDouble() / fadeWindowMs)
                    .coerceIn(0.0, 1.0)
            val progress =
                (fadeInitialProgress + (1.0 - fadeInitialProgress) * elapsedProgress).toFloat()
            applyMixState(outgoing, incoming, progress)
            if (elapsedProgress < 1.0) {
                handler.postDelayed(this, RAMP_INTERVAL_MS)
                return
            }
            finish(outgoing, incoming)
        }
    }

    /** Applies the portable curves selected by the shared planner to the two live players. */
    private fun applyMixState(outgoing: ExoPlayer, incoming: ExoPlayer, progress: Float) {
        val choreography = fadeChoreography
        if (choreography != null) {
            outgoing.volume = evaluateAutomationCurve(choreography.curves.outgoingGain, progress.toDouble())
                .toFloat().coerceIn(0f, 1f)
            incoming.volume = evaluateAutomationCurve(choreography.curves.incomingGain, progress.toDouble())
                .toFloat().coerceIn(0f, 1f)
            automateChoreography(choreography, progress)
            return
        }
        if (fadeStyle == TransitionStyle.GAPLESS) {
            automateFilters(progress)
            outgoing.volume = 1f - progress
            incoming.volume = 1f
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
    }

    private fun automateChoreography(choreography: TransitionChoreography, progress: Float) {
        val (outgoingFilter, incomingFilter) = filters() ?: return
        val curves = choreography.curves
        outgoingFilter.lowPassHz = curves.outgoingLowPass
            .takeIf { it.isNotEmpty() }
            ?.let { evaluateAutomationCurve(it, progress.toDouble()) }
            ?: TransitionFilter.OPEN
        outgoingFilter.bassGain = curves.outgoingBass
            .takeIf { it.isNotEmpty() }
            ?.let { evaluateAutomationCurve(it, progress.toDouble()) }
            ?: 1.0
        outgoingFilter.gain = 1.0
        incomingFilter.lowPassHz = TransitionFilter.OPEN
        incomingFilter.bassGain = curves.incomingBass
            .takeIf { it.isNotEmpty() }
            ?.let { evaluateAutomationCurve(it, progress.toDouble()) }
            ?: 1.0
        incomingFilter.gain = 1.0
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
        fadeInitialProgress = 0.0
        fadeChoreography = null
        stagedLiveTransition = null
        active = incoming
        standby = outgoing
        fading = false
        onHandoff(outgoing, incoming)
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f
        outgoing.pauseAtEndOfMediaItems = false
    }

    companion object {
        private const val TAG = "OrchardCrossfade"

        private const val WATCH_INTERVAL_MS = 200L
        private const val RAMP_INTERVAL_MS = 20L

        /** One ramp tick. Below this a fade is a cut, not a ramp. */
        private const val MIN_RAMP_MS = 40L

        private const val MIN_PLAYBACK_RATE = 0.01

        /** Enough muted playback to open the decoder and audio sink without wasting the intro. */
        private const val LIVE_PREROLL_SECONDS = 1.0

        /** A larger skew would put the shared planner's beat grid outside one ramp tick. */
        private const val MAX_PREROLL_DRIFT_MS = 40L

        /**
         * How far the low-pass travels toward the bass crossover by the end of the overlap. Short of
         * 1.0 on purpose: closing all the way onto the bass band leaves the outgoing track as a
         * rumble, which reads as a fault rather than as a mix.
         */
        private const val SWEEP_DEPTH = 0.85f

    }
}
