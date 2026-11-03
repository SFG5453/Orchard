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
import QtQuick.Window
import QtMultimedia

// Muted music video behind the artist hero. Starts past the intro and loops.
Item {
    id: root

    property url source: ""
    property bool playing: true
    // Fraction of the video to skip on first load; intros are often label cards or black.
    property real startAt: 0.22
    property real visibleTop: 0
    property real visibleHeight: height

    readonly property bool hasVideo: source.toString().length > 0
    // True once frames are on screen, so the fanart can stay up until then.
    readonly property bool live: hasVideo && output.sourceRect.height > 0
                                 && player.playbackState !== MediaPlayer.StoppedState
    readonly property var sink: output.videoSink
    // Frame width over height; 16:9 until the first frame arrives.
    readonly property real aspect: output.sourceRect.height > 0 ? output.sourceRect.width / output.sourceRect.height : 16 / 9
    // Visible part of the frame after cover cropping and the hero's parallax clip.
    readonly property rect frameRect: {
        const rect = output.contentRect;
        if (rect.width <= 0 || rect.height <= 0)
            return Qt.rect(0, 0, 1, 1);
        const left = Math.max(0, -rect.x / rect.width);
        const top = Math.max(0, (visibleTop - rect.y) / rect.height);
        const right = Math.min(1, (width - rect.x) / rect.width);
        const bottom = Math.min(1, (visibleTop + visibleHeight - rect.y) / rect.height);
        return Qt.rect(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }
    // Source height that fills this box at 1:1 on this screen, assuming a 16:9 frame.
    readonly property int pixelHeight: Math.ceil(Math.max(height, width * 9 / 16) * Screen.devicePixelRatio)

    signal failed()

    MediaPlayer {
        id: player

        property bool seeked: false

        source: root.source
        videoOutput: output
        audioOutput: null
        loops: MediaPlayer.Infinite
        onSourceChanged: seeked = false
        onMediaStatusChanged: {
            if (mediaStatus === MediaPlayer.LoadedMedia || mediaStatus === MediaPlayer.BufferedMedia) {
                if (!seeked && duration > 0) {
                    seeked = true;
                    if (root.startAt > 0)
                        position = Math.floor(duration * root.startAt);
                }
                if (root.playing)
                    play();
            }
        }
        onErrorOccurred: root.failed()
    }

    onPlayingChanged: {
        if (!hasVideo)
            return;
        if (playing)
            player.play();
        else
            player.pause();
    }

    VideoOutput {
        id: output

        anchors.fill: parent
        fillMode: VideoOutput.PreserveAspectCrop
    }
}
