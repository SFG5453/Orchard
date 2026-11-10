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

import QtQuick
import QtQuick.Effects
import QtMultimedia

// Because static album covers are so 2010. If the artwork isn't gently undulating,
// are you even truly experiencing the sonic landscape?
Item {
    id: root

    property url source: ""
    property real radius: 0
    property bool playing: true
    readonly property bool shouldPlay: playing && visible && !OrchardWindowState.artworkIdle
    property int fillMode: VideoOutput.PreserveAspectCrop

    readonly property bool hasVideo: Boolean(source && source.toString().length > 0)
    // Local copy of source; empty while it downloads.
    property string playableSource: ""
    // True once frames are on screen, so hosts can reshape around the loop without flashing the cover.
    readonly property bool live: videoOutput.visible && videoOutput.sourceRect.height > 0
    readonly property real videoAspect: live ? videoOutput.sourceRect.width / videoOutput.sourceRect.height : 0

    MediaPlayer {
        id: player

        source: root.playableSource
        videoOutput: videoOutput
        audioOutput: null // Keep it strictly silent; the music stream handles the auditory pleasure.
        loops: MediaPlayer.Infinite
        // A loop that won't decode is dropped from both caches so the next play asks the mirrors.
        onErrorOccurred: function(error, errorString) {
            if (error !== MediaPlayer.NetworkError && root.hasVideo) {
                OrchardArtworkVideos.discard(root.source.toString());
                OrchardAnimatedArtwork.forgetUrl(root.source.toString());
            }
        }
    }

    function resolvePlayable() {
        playableSource = hasVideo ? OrchardArtworkVideos.localUrl(source.toString()) : "";
        if (playableSource.length > 0 && shouldPlay)
            player.play();
        else if (playableSource.length === 0)
            player.stop();
        else
            player.pause();
    }

    Component.onCompleted: resolvePlayable()

    Connections {
        target: OrchardArtworkVideos

        function onReady(remote, local) {
            if (root.hasVideo && remote === root.source.toString())
                root.resolvePlayable();
        }

        // Only content errors mean a stale URL; going offline must not wipe good entries.
        function onFailed(remote, permanent) {
            if (permanent && root.hasVideo && remote === root.source.toString())
                OrchardAnimatedArtwork.forgetUrl(remote);
        }
    }

    Connections {
        target: root

        function onShouldPlayChanged() {
            if (root.playableSource.length === 0) return;
            if (root.shouldPlay) {
                player.play();
            } else {
                player.pause();
            }
        }

        function onSourceChanged() {
            root.resolvePlayable();
        }
    }

    VideoOutput {
        id: videoOutput

        anchors.fill: parent
        fillMode: root.fillMode
        visible: root.hasVideo && player.playbackState !== MediaPlayer.StoppedState
        layer.enabled: root.radius > 0
        layer.effect: MultiEffect {
            maskEnabled: root.radius > 0
            maskSource: mask
        }
    }

    Rectangle {
        id: mask

        anchors.fill: parent
        radius: root.radius
        visible: false
        layer.enabled: root.radius > 0
    }
}
