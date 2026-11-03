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


import Orchard
import QtQuick
import QtMultimedia

// The one YouTube video decoder. The theater and the floating window each point it at their own picture.
MediaPlayer {
    id: video

    // One automatic re-resolve per stream; signed URLs expire mid-session.
    property bool retried: false
    readonly property bool loading: mediaStatus === MediaPlayer.LoadingMedia || mediaStatus === MediaPlayer.StalledMedia

    function syncVideo() {
        if (video.mediaStatus === MediaPlayer.NoMedia || video.mediaStatus === MediaPlayer.LoadingMedia)
            return;
        const target = OrchardPlayback.position * 1000;
        // The audio engine plays the soundtrack and is the clock; the muted picture follows it.
        if (Math.abs(video.position - target) > 400)
            video.position = target;
        if (OrchardPlayback.playing && video.playbackState !== MediaPlayer.PlayingState)
            video.play();
        else if (!OrchardPlayback.playing && video.playbackState === MediaPlayer.PlayingState)
            video.pause();
    }

    source: OrchardMusicVideo.source
    onSourceChanged: retried = false
    onMediaStatusChanged: if (mediaStatus === MediaPlayer.LoadedMedia || mediaStatus === MediaPlayer.BufferedMedia) syncVideo()
    onErrorOccurred: {
        if (!retried) {
            retried = true;
            OrchardMusicVideo.retry();
        }
    }

    readonly property Timer syncTimer: Timer {
        interval: 250
        repeat: true
        running: OrchardMusicVideo.status === "ready"
        onTriggered: video.syncVideo()
    }
}
