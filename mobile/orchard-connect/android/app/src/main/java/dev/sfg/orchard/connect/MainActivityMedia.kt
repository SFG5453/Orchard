package dev.sfg.orchard.connect

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import org.json.JSONObject

internal fun MainActivity.deviceDisplayName(): String {
    val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").trim()
    return model.ifEmpty { "Phone" }
}

internal fun MainActivity.setupMediaSession() {
    mediaSession = MediaSession(this, "OrchardConnect").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { send("play-pause") }
            override fun onPause() { send("play-pause") }
            override fun onSkipToNext() { send("next") }
            override fun onSkipToPrevious() { send("previous") }
            override fun onSeekTo(pos: Long) { send("seek", pos / 1000.0) }
        })
        isActive = true
    }
}

internal fun MainActivity.updateMediaSession(track: JSONObject, playback: JSONObject) {
    val session = mediaSession ?: return

    val isPlaying = playback.optBoolean("isPlaying", false)
    val position = (playback.optDouble("currentTime", 0.0) * 1000).toLong()
    val duration = (playback.optDouble("duration", 0.0) * 1000).toLong()
    val actions = PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_SEEK_TO

    session.setPlaybackState(
        PlaybackState.Builder()
            .setActions(actions)
            .setState(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, 1.0f)
            .build()
    )

    val metaBuilder = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, track.optString("title", "Unknown Title"))
        .putString(MediaMetadata.METADATA_KEY_ARTIST, track.optString("artist", "Unknown Artist"))
        .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)

    currentArtwork?.let { metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
    session.setMetadata(metaBuilder.build())
}
