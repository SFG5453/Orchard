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

    private var active: ExoPlayer? = null
    private var standby: ExoPlayer? = null
    private var fading = false
    private var fadeStartedAt = 0L
    private var fadeWindowMs = 0L
    private var fadeStyle = TransitionStyle.EQUAL_POWER

    /** Begins watching [active] for the end of each track. Safe to call again to re-seat the pair. */
    fun start(active: ExoPlayer, standby: ExoPlayer) {
        this.active = active
        this.standby = standby
        fading = false
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
        handler.removeCallbacks(watcher)
        handler.removeCallbacks(ramp)
        fading = false
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
                if (!settings.enabled) onPlan(null)
                return
            }
            // Repeating one track would fade it into itself.
            if (player.repeatMode == Player.REPEAT_MODE_ONE) return
            val duration = player.duration
            if (duration == C.TIME_UNSET) return
            if (player.nextMediaItemIndex == C.INDEX_UNSET) return
            // A track shorter than two fades would spend most of itself fading.
            if (duration < settings.fadeSeconds * 2000) return

            val plan = planFor(player, settings, duration)
            onPlan(plan.takeIf { it.markerVisible && !it.blocked })

            if (plan.blocked || !plan.shouldStart) return
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
            if (prepared != null && beginRenderedTransition(plan, prepared)) {
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
            beginFade(plan, remainingMs = endMs - player.currentPosition)
        }
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
     * resume where the mix left it. ExoPlayer's own item transition then covers the join rather
     * than anything here having to.
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
    private fun beginRenderedTransition(
        plan: TransitionPlan,
        prepared: TransitionPreparer.Prepared,
    ): Boolean {
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

        fading = true
        fadeStartedAt = SystemClock.elapsedRealtime()
        fadeWindowMs = SPLICE_FADE_MS
        fadeStyle = TransitionStyle.GAPLESS
        outgoing.pauseAtEndOfMediaItems = true

        incoming.volume = 1f
        incoming.repeatMode = outgoing.repeatMode
        incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
        incoming.setPlaylistMetadata(outgoing.playlistMetadata)
        incoming.setMediaItems(playlist, currentIndex, 0L)
        incoming.setPlaybackParameters(PlaybackParameters.DEFAULT)
        incoming.prepare()
        incoming.play()
        handler.post(ramp)
        return true
    }

    private fun planFor(player: ExoPlayer, settings: Config, durationMs: Long): TransitionPlan {
        val currentTrack = player.currentMediaItem?.let(MediaItemMapper::toTrack)
        val nextTrack = player.getMediaItemAt(player.nextMediaItemIndex).let(MediaItemMapper::toTrack)
        return planTransition(
            analysis = currentTrack?.let(analysisFor) ?: TrackAnalysis(),
            nextAnalysis = analysisFor(nextTrack),
            currentTrack = currentTrack,
            nextTrack = nextTrack,
            currentTime = player.currentPosition / 1000.0,
            duration = durationMs / 1000.0,
            fadeSeconds = settings.fadeSeconds,
            mode = settings.mode,
            albumSequential = isAlbumPlaythrough(player, currentTrack),
        )
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
        private const val RAMP_INTERVAL_MS = 40L

        /** One ramp tick. Below this a fade is a cut, not a ramp. */
        private const val MIN_RAMP_MS = 40L

        /**
         * How long the live outgoing track takes to give way to the rendered buffer. Short enough
         * that two phase-divergent copies of the same audio never overlap audibly, long enough that
         * the cut is not a click.
         */
        private const val SPLICE_FADE_MS = 60L

        /**
         * How far the low-pass travels toward the bass crossover by the end of the overlap. Short of
         * 1.0 on purpose: closing all the way onto the bass band leaves the outgoing track as a
         * rumble, which reads as a fault rather than as a mix.
         */
        private const val SWEEP_DEPTH = 0.85f

    }
}
