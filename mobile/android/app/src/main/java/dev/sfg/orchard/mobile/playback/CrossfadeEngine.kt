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

import android.media.MediaDataSource
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.smart.CrossfadeMode
import dev.sfg.orchard.mobile.playback.smart.MixSplicer
import dev.sfg.orchard.mobile.playback.smart.PreparedMix
import kotlin.math.max

/**
 * Track transitions across a pair of ExoPlayers, as desktop's `PlaybackController` runs them.
 *
 * Standard mode is a trailing equal-power fade. Adaptive mode asks [AdaptiveMixer] (the desktop
 * worker, in process) for a rendered overlap and splices it in with [MixSplicer]; when the shared
 * planner keeps the natural boundary the song simply ends, and when preparation fails the
 * standard fade stands in, exactly the three outcomes desktop has.
 *
 * The caller owns both players and is told, via [onHandoff], to move the media session onto the
 * incoming one.
 */
@UnstableApi
class CrossfadeEngine(
    private val handler: Handler,
    private val config: () -> Config,
    private val mixer: AdaptiveMixer,
    /** A fully cached source for a queue item, or null while it is still downloading. */
    private val sourceFor: (Uri) -> (() -> MediaDataSource?)?,
    private val splicerFor: (ExoPlayer) -> MixSplicer?,
    private val onMarker: (TransitionMarker?) -> Unit = {},
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
    private val canCrossfadeTo: (String) -> Boolean = { true },
) {
    /** What the listener asked for, read fresh on every tick so a settings change lands at once. */
    data class Config(val enabled: Boolean, val fadeSeconds: Double, val mode: CrossfadeMode)
    private var active: ExoPlayer? = null
    private var standby: ExoPlayer? = null

    // Standard fade. The gains run in each player's MixSplicer; this side only stages and hands off.
    private var staged = false
    private var stagedQueue: List<String> = emptyList()
    private var fading = false
    private var fadeEndMs = 0L
    private var fadeSlackMs = 0L
    private var outgoingEnded = false
    private var armedThisTick = false

    // Adaptive splice in flight.
    private var splice: Splice? = null
    private class Splice(
        val mix: PreparedMix.Ready,
        val outgoing: ExoPlayer,
        val incoming: ExoPlayer,
        val outgoingSplicer: MixSplicer,
        val incomingSplicer: MixSplicer,
        val incomingStart: Double,
        var playing: Boolean = false,
        var correctedAt: Long = 0L,
        val startedAt: Long = SystemClock.elapsedRealtime(),
    )

    /** Begins watching [active] for the end of each track. Safe to call again to re-seat the pair. */
    fun start(active: ExoPlayer, standby: ExoPlayer) {
        this.active = active
        this.standby = standby
        staged = false
        fading = false
        splice = null
        handler.removeCallbacks(watcher)
        cancelTicks()
        active.volume = 1f
        active.pauseAtEndOfMediaItems = false
        handler.post(watcher)
    }

    /**
     * Drops an in-flight transition and leaves the active player untouched. Anything that
     * invalidates the snapshot the standby was loaded with (a skip, a seek, a queue edit) must call this.
     * A prepared mix stays: it is keyed by pair, and a seek back before its start replays it, as on desktop.
     */
    fun abort() {
        onMarker(null)
        val wasBusy = staged || fading || splice != null
        active?.removeListener(endWatcher)
        staged = false
        fading = false
        splice = null
        cancelTicks()
        active?.let { splicerFor(it)?.disarm() }
        standby?.let { splicerFor(it)?.disarm() }
        if (!wasBusy) return
        active?.let {
            it.volume = 1f
            it.pauseAtEndOfMediaItems = false
        }
        standby?.let {
            it.stop()
            it.clearMediaItems()
            it.volume = 1f
        }
    }

    fun release() {
        mixer.release()
        active?.removeListener(endWatcher)
        handler.removeCallbacks(watcher)
        cancelTicks()
        staged = false
        fading = false
        splice = null
        active = null
        standby = null
    }

    private fun cancelTicks() {
        handler.removeCallbacks(fadeStart)
        handler.removeCallbacks(fadeTick)
        handler.removeCallbacks(spliceTick)
    }

    /** Frees the mixer's model memory; the next adaptive pair reloads it. */
    fun trimMemory() = mixer.trimMemory()

    private val watcher = object : Runnable {
        override fun run() {
            handler.postDelayed(this, WATCH_INTERVAL_MS)
            tick()
        }
    }

    // Starts a staged fade on time when it falls between two watcher ticks.
    private val fadeStart = Runnable { tick() }

    private fun tick() {
        if (fading || splice != null) return
        val player = active ?: return
        armedThisTick = false
        check(player)
        // Staged on an earlier tick but no longer wanted: the setting went off, or the pair changed.
        if (staged && !armedThisTick) abort()
    }

    private fun check(player: ExoPlayer) {
        val settings = config()
        if (!settings.enabled) {
            onMarker(null)
            return
        }
        // Paused or buffering: keep the staged deck so resuming does not reload it.
        if (!player.isPlaying) {
            armedThisTick = staged
            return
        }
        val nextIndex = player.nextMediaItemIndex
        // Video needs the session's surface, and repeating one track would fade it into itself.
        if (nextIndex == C.INDEX_UNSET || player.repeatMode == Player.REPEAT_MODE_ONE ||
            !canCrossfadeTo(player.getMediaItemAt(nextIndex).mediaId) ||
            MediaItemMapper.isVideoUri(player.currentMediaItem?.localConfiguration?.uri) ||
            MediaItemMapper.isVideoUri(player.getMediaItemAt(nextIndex).localConfiguration?.uri)
        ) {
            onMarker(null)
            return
        }
        val durationMs = player.sourceDurationMs()
        if (durationMs <= 0) return
        if (settings.mode == CrossfadeMode.SMART) adaptive(player, nextIndex, settings, durationMs)
        else standard(player, settings, durationMs)
    }

    private fun standard(player: ExoPlayer, settings: Config, durationMs: Long) {
        val fadeMs = (settings.fadeSeconds * 1000).toLong()
        if (durationMs <= fadeMs) return
        val startMs = durationMs - fadeMs
        currentPair(player)?.let { (outgoing, incoming) ->
            onMarker(TransitionMarker(outgoing.id, startMs, durationMs, "equal_power", incoming.id,
                renderedDurationMs = fadeMs))
        }
        val position = player.sourcePositionMs()
        if (position + STAGE_LEAD_SECONDS * 1000 < startMs) return
        // The standby copied the queue when staged; an edit since then would play the old one.
        if (staged && player.queueMediaIds() != stagedQueue) {
            abort()
            return
        }
        val splicer = splicerFor(player) ?: return
        val fade = splicer.fade ?: outgoingFade(startMs, durationMs, position)
        if (!staged && !stage(player, fade)) return
        // Armed seconds early, so the outgoing sink's first faded frames are already shaped.
        splicer.fade = fade
        armedThisTick = true
        val wait = fade.startUs / 1000 - position
        handler.removeCallbacks(fadeStart)
        if (wait <= START_SLACK_MS) beginFade(player, fade)
        else if (wait < WATCH_INTERVAL_MS) handler.postDelayed(fadeStart, wait)
    }

    private fun outgoingFade(startMs: Long, durationMs: Long, position: Long): MixSplicer.Fade {
        // Seeked into the fade window: fade over what is left.
        val from = max(startMs, position)
        val window = (durationMs - from).coerceAtLeast(MIN_RAMP_MS)
        return MixSplicer.Fade(from * 1000, window * 1000, fadeIn = false)
    }

    /** Loads the next song paused on the standby, with its fade-in armed, so it starts on cue. */
    private fun stage(outgoing: ExoPlayer, fade: MixSplicer.Fade): Boolean {
        val incoming = standby ?: return false
        val incomingSplicer = splicerFor(incoming) ?: return false
        val nextIndex = outgoing.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val queue: List<MediaItem> = (0 until outgoing.mediaItemCount).map(outgoing::getMediaItemAt)
        // The incoming player owns what plays next, so the outgoing one must not advance on its own.
        outgoing.pauseAtEndOfMediaItems = true
        incomingSplicer.fade = MixSplicer.Fade(0, fade.durationUs, fadeIn = true)
        incoming.pause()
        incoming.volume = 1f
        incoming.repeatMode = outgoing.repeatMode
        incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
        incoming.setPlaylistMetadata(outgoing.playlistMetadata)
        incoming.setMediaItems(queue, nextIndex, 0)
        incoming.prepare()
        stagedQueue = queue.map(MediaItem::mediaId)
        staged = true
        return true
    }

    private fun adaptive(player: ExoPlayer, nextIndex: Int, settings: Config, durationMs: Long) {
        val (current, next) = currentPair(player) ?: return
        val duration = durationMs / 1000.0
        // Speech, live material and short tracks keep their natural boundaries, as on desktop.
        val context = "${current.title} ${next.title}"
        if (duration < 45 || AdaptiveMixer.EXCLUDED.containsMatchIn(context)) {
            onMarker(null)
            return
        }
        val position = player.sourcePositionMs() / 1000.0
        val key = "${current.id}\n${next.id}"
        val result = mixer.resultFor(key)
        if (result == null) {
            onMarker(null)
            if (mixer.isPreparing(key) || duration - position > PREPARE_LEAD_SECONDS) return
            val outgoing = player.currentMediaItem?.localConfiguration?.uri?.let(sourceFor) ?: return
            val incoming = player.getMediaItemAt(nextIndex).localConfiguration?.uri?.let(sourceFor) ?: return
            mixer.prepare(key, outgoing, incoming, AdaptiveMixer.request(
                current, next, duration, position, settings.fadeSeconds,
                albumSequential = isAlbumPlaythrough(player, current),
            ))
            return
        }
        when (result) {
            is PreparedMix.NoMix -> onMarker(null)
            is PreparedMix.Failed -> standard(player, settings, durationMs)
            is PreparedMix.Ready -> scheduleSplice(player, nextIndex, result, current, next, position)
        }
    }

    private fun scheduleSplice(
        player: ExoPlayer, nextIndex: Int, mix: PreparedMix.Ready, current: Track, next: Track, position: Double,
    ) {
        onMarker(TransitionMarker(
            trackId = current.id,
            startMs = (mix.outgoingStart * 1000).toLong(),
            endMs = ((mix.outgoingStart + mix.duration) * 1000).toLong(),
            style = when (mix.strategy) {
                "filtered blend" -> "dj_filter"
                "beatmatched crossfade", "bass swap" -> "dj_blend"
                else -> "equal_power"
            },
            incomingTrackId = next.id,
            incomingCueMs = (mix.incomingCue * 1000).toLong(),
            renderedDurationMs = (mix.duration * 1000).toLong(),
            // Past its start the mix will not play, so stop promising it.
            prepared = position - mix.outgoingStart <= STALE_SECONDS,
        ))
        // A result made stale by a seek is not permission to jump into the middle of a mix.
        if (position - mix.outgoingStart > STALE_SECONDS) return
        val outgoingSplicer = splicerFor(player) ?: return
        val incoming = standby ?: return
        val incomingSplicer = splicerFor(incoming) ?: return
        if (outgoingSplicer.outgoing == null) {
            outgoingSplicer.outgoing = MixSplicer.Outgoing(mix.pcm, (mix.outgoingStart * mix.rate).toLong(), mix.rate)
        }
        // The incoming player starts muted a little before the render, so its startup is inaudible.
        val incomingStart = (mix.incomingCue - INCOMING_LEAD_SECONDS).coerceAtLeast(0.0)
        if (mix.outgoingStart - position > STAGE_LEAD_SECONDS) return
        val queue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        incoming.pause()
        incoming.volume = 1f
        incoming.repeatMode = player.repeatMode
        incoming.shuffleModeEnabled = player.shuffleModeEnabled
        incoming.setPlaylistMetadata(player.playlistMetadata)
        incomingSplicer.incoming = MixSplicer.Incoming((mix.incomingResume * mix.incomingRate).toLong(), mix.incomingRate)
        incoming.setMediaItems(queue, nextIndex, (incomingStart * 1000).toLong())
        incoming.prepare()
        // The render owns the end of this song; the player must not advance on its own.
        player.pauseAtEndOfMediaItems = true
        splice = Splice(mix, player, incoming, outgoingSplicer, incomingSplicer, incomingStart)
        Log.i(TAG, "AdaptiveMix: plan ${current.title} -> ${next.title} | ${mix.log}")
        handler.post(spliceTick)
    }

    private val spliceTick = object : Runnable {
        override fun run() {
            val s = splice ?: return
            if (s.incoming.playerError != null || s.outgoing.playerError != null) {
                abort()
                return
            }
            val outgoingSeconds = s.outgoing.sourcePositionMs() / 1000.0
            val mix = s.mix
            // Start the incoming clock so its cue plays under render frame zero.
            if (!s.playing && outgoingSeconds >= mix.outgoingStart - (mix.incomingCue - s.incomingStart)) {
                s.incoming.play()
                s.playing = true
            }
            if (s.playing) {
                // Pause and resume follow the session player, so the two clocks stay together.
                s.incoming.playWhenReady = s.outgoing.playWhenReady
                align(s, outgoingSeconds)
            }
            val renderEnd = mix.outgoingStart + mix.duration
            val drained = outgoingSeconds >= renderEnd + DRAIN_SECONDS ||
                s.outgoing.playbackState == Player.STATE_ENDED
            val overdue = SystemClock.elapsedRealtime() - s.startedAt >
                ((mix.outgoingStart - outgoingSeconds).coerceAtLeast(0.0) + mix.duration + OVERDUE_SECONDS) * 1000
            if ((s.incomingSplicer.unmuted && drained) || (overdue && s.incomingSplicer.unmuted)) {
                finishSplice(s)
                return
            }
            if (overdue && !s.incomingSplicer.unmuted && s.outgoingSplicer.cutFrame < 0) {
                // The render never took over (armed too late); the song plays out naturally.
                Log.w(TAG, "AdaptiveMix: missed the splice; keeping the natural boundary")
                abort()
                return
            }
            handler.postDelayed(this, SPLICE_TICK_MS)
        }
    }

    /**
     * Keeps the muted incoming player on the render's clock: its media time should read the cue
     * plus how far the render has played. Corrections retime its muted stream, so they wait out
     * one sink buffer before measuring again.
     */
    private fun align(s: Splice, outgoingSeconds: Double) {
        if (s.incomingSplicer.unmuted || !s.incoming.isPlaying || !s.outgoing.isPlaying) return
        val now = SystemClock.elapsedRealtime()
        if (now - s.correctedAt < CORRECTION_INTERVAL_MS) return
        val mix = s.mix
        val cut = s.outgoingSplicer.cutFrame
        val renderSeconds = if (cut >= 0) {
            (outgoingSeconds * mix.rate - cut + s.outgoingSplicer.cutRenderFrame) / mix.rate
        } else outgoingSeconds - mix.outgoingStart
        val delta = s.incoming.currentPosition / 1000.0 - (mix.incomingCue + renderSeconds)
        if (kotlin.math.abs(delta) < ALIGN_TOLERANCE_SECONDS) return
        s.incomingSplicer.shift(Math.round(-delta * mix.incomingRate))
        s.correctedAt = now
    }

    private fun finishSplice(s: Splice) {
        handler.removeCallbacks(spliceTick)
        splice = null
        s.outgoingSplicer.disarm()
        s.incomingSplicer.disarm()
        s.incoming.pauseAtEndOfMediaItems = false
        handOff(s.outgoing, s.incoming)
    }

    /** Swaps the decks' roles, moves the session, and empties the outgoing deck. */
    private fun handOff(outgoing: ExoPlayer, incoming: ExoPlayer) {
        active = incoming
        standby = outgoing
        onMarker(null)
        mixer.forget()
        onHandoff(outgoing, incoming)
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.pauseAtEndOfMediaItems = false
    }

    /** The outgoing and incoming tracks of the transition about to happen. */
    private fun currentPair(player: ExoPlayer): Pair<Track, Track>? {
        val outgoing = player.currentMediaItem?.let(MediaItemMapper::toTrack) ?: return null
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return null
        return outgoing to MediaItemMapper.toTrack(player.getMediaItemAt(nextIndex))
    }

    private fun beginFade(outgoing: ExoPlayer, fade: MixSplicer.Fade) {
        val incoming = standby ?: return
        fading = true
        outgoingEnded = false
        fadeEndMs = (fade.startUs + fade.durationUs) / 1000
        fadeSlackMs = (fade.durationUs / 1000 / 20).coerceAtMost(DONE_SLACK_MS)
        outgoing.addListener(endWatcher)
        incoming.playWhenReady = outgoing.playWhenReady
        handler.post(fadeTick)
    }

    /** Pause and resume follow the session player; its pause at the end of the song is the cue to hand off. */
    private val endWatcher = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) outgoingEnded = true
            else if (fading) standby?.playWhenReady = playWhenReady
        }
    }

    private val fadeTick = object : Runnable {
        override fun run() {
            val outgoing = active ?: return
            val incoming = standby ?: return
            // Fading into a stream that failed to load would just be a fade to silence.
            if (incoming.playerError != null) {
                abort()
                return
            }
            val done = outgoingEnded || outgoing.playbackState == Player.STATE_ENDED ||
                outgoing.sourcePositionMs() >= fadeEndMs - fadeSlackMs
            if (!done) {
                handler.postDelayed(this, FADE_TICK_MS)
                return
            }
            outgoing.removeListener(endWatcher)
            staged = false
            fading = false
            handOff(outgoing, incoming)
            // After stop(): nothing is left in the outgoing sink for a cleared fade to touch.
            splicerFor(outgoing)?.disarm()
        }
    }

    companion object {
        private const val TAG = "OrchardCrossfade"

        private const val WATCH_INTERVAL_MS = 200L
        private const val SPLICE_TICK_MS = 20L

        /** Handoff polling only; the audible ramp runs on the audio thread. */
        private const val FADE_TICK_MS = 50L

        /** Watcher lateness tolerated before a staged fade starts. */
        private const val START_SLACK_MS = 25L

        /** Outgoing tail left when the handoff fires; inaudible at the bottom of a cosine. */
        private const val DONE_SLACK_MS = 100L

        /** Below this a fade is a cut, not a ramp. */
        private const val MIN_RAMP_MS = 40L

        /** Desktop resolves and prepares the next song within two minutes of the end. */
        private const val PREPARE_LEAD_SECONDS = 120.0

        /** Matches desktop's "position - fadeStart > 0.25" staleness guard. */
        private const val STALE_SECONDS = 0.25

        /** How early the incoming player is loaded and buffered before it is due. */
        private const val STAGE_LEAD_SECONDS = 5.0

        /** Muted incoming playback before the render, enough to open its decoder and sink. */
        private const val INCOMING_LEAD_SECONDS = 1.0

        /** Outgoing playback past the render's end before its sink is surely empty. */
        private const val DRAIN_SECONDS = 0.3

        /** Past the render's end with no handoff: something stalled. */
        private const val OVERDUE_SECONDS = 5.0

        /** A sink buffer; retiming sooner would measure the previous correction half-applied. */
        private const val CORRECTION_INTERVAL_MS = 300L
        private const val ALIGN_TOLERANCE_SECONDS = 0.002
    }
}
