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
    property int fillMode: VideoOutput.PreserveAspectCrop

    readonly property bool hasVideo: Boolean(source && source.toString().length > 0)
    // True once frames are on screen, so hosts can reshape around the loop without flashing the cover.
    readonly property bool live: videoOutput.visible && videoOutput.sourceRect.height > 0
    readonly property real videoAspect: live ? videoOutput.sourceRect.width / videoOutput.sourceRect.height : 0

    MediaPlayer {
        id: player

        source: root.source
        videoOutput: videoOutput
        audioOutput: null // Keep it strictly silent; the music stream handles the auditory pleasure.
        loops: MediaPlayer.Infinite
        // A stored URL that stopped loading is stale; drop it so the next play asks the mirrors.
        // Network errors are skipped so going offline doesn't wipe good entries.
        onErrorOccurred: function(error, errorString) {
            if (error !== MediaPlayer.NetworkError && root.hasVideo)
                OrchardAnimatedArtwork.forgetUrl(root.source.toString());
        }

        Component.onCompleted: {
            if (root.playing && root.hasVideo) {
                player.play();
            }
        }
    }

    Connections {
        target: root

        function onPlayingChanged() {
            if (!root.hasVideo) return;
            if (root.playing) {
                player.play();
            } else {
                player.pause();
            }
        }

        function onSourceChanged() {
            if (root.hasVideo && root.playing) {
                player.play();
            } else {
                player.stop();
            }
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
