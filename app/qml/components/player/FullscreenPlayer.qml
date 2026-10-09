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

import ".."
import Orchard
import QtQuick
import QtQuick.Window

// Full-window now playing. Layout lives in FullscreenPlayerForm.ui.qml; this file binds playback and handles input.
// The form is the stage, this file is the stagehand.
FullscreenPlayerForm {
    id: root

    open: false

    signal closeRequested()
    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)

    readonly property real mixStart: OrchardPlayback.mixStart
    readonly property real mixDuration: OrchardPlayback.mixDuration
    readonly property string trackId: OrchardPlayback.track.id || ""
    readonly property bool fading: OrchardPlayback.crossfadeActive
    property double fadeEndedAt: 0

    currentTrack: OrchardPlayback.track
    transitionTrack: OrchardPlayback.transitionTrack
    position: OrchardPlayback.position
    duration: OrchardPlayback.duration
    transitionPosition: OrchardPlayback.transitionPosition
    transitionDuration: OrchardPlayback.transitionDuration
    playing: OrchardPlayback.playing
    crossfadeActive: OrchardPlayback.crossfadeActive
    crossfadeProgress: OrchardPlayback.crossfadeProgress
    queueOriginTitle: String((OrchardPlayback.track.queueOrigin || ({})).title || "")
    animatedArtworkEnabled: OrchardAppearance.animatedArtworkEnabled
    animatedArtworkUrl: OrchardPlayback.animatedArtworkUrl
    canvasLoop: /\.cnvs\.mp4/.test(OrchardPlayback.animatedArtworkUrl || "")

    // Qobuz songs show their tier instead of kbps. Untyped for qmlcachegen's sake.
    function sourceQuality(track) {
        if (!track || track.playbackSource !== "qobuz")
            return "";
        const parts = [track.hires ? qsTr("Hi-Res") : qsTr("Lossless")];
        if (track.bitDepth || track.sampleRate) {
            const depth = track.bitDepth ? qsTr("%1-bit").arg(track.bitDepth) : "";
            const rate = track.sampleRate ? qsTr("%1 kHz").arg(Number((track.sampleRate / 1000).toFixed(1))) : "";
            parts.push([depth, rate].filter(Boolean).join(" / "));
        }
        if (track.streamCodec)
            parts.push(track.streamCodec);
        return parts.join(" · ");
    }

    // Streamed songs: bitrate then codec, e.g. "128 kbps · Opus". Needs the bitrate setting.
    function streamQuality(track) {
        if (!OrchardAppearance.showBitrate || OrchardPlayback.bitrate <= 0)
            return "";
        const kbps = Math.round(OrchardPlayback.bitrate >= 1000 ? OrchardPlayback.bitrate / 1000 : OrchardPlayback.bitrate);
        return track && track.streamCodec ? kbps + " kbps · " + track.streamCodec : kbps + " kbps";
    }

    function artistLabel(track) {
        return track.artist || (track.artists || []).join(", ");
    }

    function timeLabel(seconds) {
        if (!Number.isFinite(seconds) || seconds < 0)
            return "0:00";
        const total = Math.floor(seconds);
        return Math.floor(total / 60) + ":" + String(total % 60).padStart(2, "0");
    }

    function togglePane(name) {
        pane = pane === name ? "" : name;
    }

    onOpenChanged: if (open) forceActiveFocus()

    // Swaps lyrics and queue without collapsing the split.
    onPaneChanged: {
        if (pane === "" || pane === shownPane)
            return;
        if (split < 0.01) {
            shownPane = pane;
            paneSwapIn.restart();
        } else {
            paneSwapAnim.restart();
        }
    }

    onCanvasLiveChanged: if (canvasLive && canvasItem.videoAspect > 0) canvasAspect = Math.max(0.5, Math.min(1, canvasItem.videoAspect))

    onFadingChanged: if (!fading) fadeEndedAt = Date.now()
    onTrackIdChanged: {
        if (!visible || fading || Date.now() - fadeEndedAt < 500)
            return;
        bumpAnimation.restart();
    }

    MediaMenu {
        id: mediaMenu
        media: OrchardPlayback.track
        onAlbumRequested: function(album) { root.albumRequested(album); }
        onArtistRequested: function(artist) { root.artistRequested(artist); }
        onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
    }

    SequentialAnimation {
        id: paneSwapAnim
        NumberAnimation { target: root; property: "paneSwap"; to: 0; duration: 140; easing.type: Easing.InCubic }
        ScriptAction { script: root.shownPane = root.pane }
        NumberAnimation { target: root; property: "paneSwap"; to: 1; duration: 380; easing.type: Easing.OutCubic }
    }

    NumberAnimation { id: paneSwapIn; target: root; property: "paneSwap"; from: 0; to: 1; duration: 520; easing.type: Easing.OutCubic }

    blocker.onWheel: function(wheel) { wheel.accepted = true; }

    dragHandler.enabled: root.Window.window !== null && root.Window.window.visibility !== Window.FullScreen
    dragHandler.onActiveChanged: if (dragHandler.active) root.Window.window.startSystemMove()

    backdrop.warpRunning: root.visible && OrchardPlayback.playing && root.Window.window !== null && root.Window.window.visibility !== Window.Minimized
    backdrop.warpSpeed: OrchardAppearance.speed
    backdrop.warpIntensity: OrchardAppearance.intensity
    backdrop.warpSaturation: OrchardAppearance.saturation
    backdrop.warpBrightness: OrchardAppearance.brightness

    closeButton.onClicked: root.closeRequested()

    artArea.onClicked: function(mouse) {
        if (mouse.button === Qt.RightButton)
            mediaMenu.popup(root.artArea, mouse.x, mouse.y);
        else
            OrchardPlayback.toggle();
    }

    queuePane.queueItems: OrchardPlayback.queue || []
    queuePane.autoplayEnabled: OrchardPlayback.autoplayEnabled
    queuePane.clearButton.onClicked: OrchardPlayback.clearQueue()
    queuePane.list.delegate: FullscreenQueueRow {
        required property var modelData
        required property int index
        width: root.queuePane.list.width - 8
        track: modelData
        rowIndex: index
        artistText: root.artistLabel(modelData)
        paneSwap: root.paneSwap
        accentColor: root.accentColor
        primaryText: root.primaryText
        mutedText: root.mutedText
        onClicked: OrchardPlayback.playQueueIndex(index)
        removeButton.onClicked: OrchardPlayback.removeFromQueue(index)
    }

    controls.seekLimit: mixStart >= 0 ? Math.max(0, mixStart - 5, OrchardPlayback.position) : OrchardPlayback.duration
    controls.seekEnabled: OrchardPlayback.duration > 0 && !OrchardPlayback.loading && !OrchardPlayback.errorMessage && !root.mixing
    controls.positionText: timeLabel(controls.scrubbing ? controls.scrubPosition : root.shownPosition)
    controls.remainingText: root.shownDuration > 0
        ? "-" + timeLabel(root.shownDuration - (controls.scrubbing ? controls.scrubPosition : root.shownPosition)) : "-:--"
    controls.mixDurationText: mixDuration > 0 ? timeLabel(mixDuration) : ""
    controls.qualityText: sourceQuality(OrchardPlayback.track) || streamQuality(OrchardPlayback.track)
    controls.volume: OrchardHome.volume

    controls.progress.onPressedChanged: if (!controls.progress.pressed && controls.progress.enabled) OrchardPlayback.seek(controls.scrubPosition)
    controls.volumeSlider.onMoved: OrchardHome.volume = controls.volumeSlider.value
    controls.volumeWheel.onWheel: function(event) {
        const delta = event.angleDelta.y !== 0 ? event.angleDelta.y : -event.angleDelta.x;
        const step = (event.inverted ? -delta : delta) / 120 * 0.05;
        OrchardHome.volume = Math.max(0, Math.min(1, OrchardHome.volume + step));
    }

    controls.mixLetters: qsTr("Mixing").split("")
    controls.currentInfo.artistText: artistLabel(OrchardPlayback.track)
    controls.incomingInfo.artistText: artistLabel(OrchardPlayback.transitionTrack)
    controls.currentInfo.interactive: true
    controls.currentInfo.albumLinked: Boolean(mediaMenu.albumId)
    controls.currentInfo.artistLinked: mediaMenu.artistIds.length > 0
    controls.currentInfo.titleArea.onClicked: function(mouse) {
        if (mouse.button === Qt.RightButton)
            mediaMenu.popup(controls.currentInfo.titleArea, mouse.x, mouse.y);
        else
            mediaMenu.viewAlbum();
    }
    controls.currentInfo.artistArea.onClicked: function(mouse) {
        if (mouse.button === Qt.RightButton)
            mediaMenu.popup(controls.currentInfo.artistArea, mouse.x, mouse.y);
        else
            mediaMenu.viewArtist(controls.currentInfo.artistArea, mouse.x, mouse.y);
    }
    controls.currentInfo.albumArea.onClicked: function(mouse) {
        if (mouse.button === Qt.RightButton)
            mediaMenu.popup(controls.currentInfo.albumArea, mouse.x, mouse.y);
        else
            mediaMenu.viewAlbum();
    }

    controls.moreButton.enabled: Boolean(OrchardPlayback.track.id)
    controls.moreButton.onClicked: mediaMenu.popup(controls.moreButton, 0, controls.moreButton.height)

    controls.shuffleButton.modeActive: OrchardPlayback.shuffleEnabled
    controls.shuffleButton.tooltip: OrchardPlayback.shuffleEnabled ? qsTr("Shuffle on") : qsTr("Shuffle off")
    controls.shuffleButton.onClicked: OrchardPlayback.shuffleEnabled = !OrchardPlayback.shuffleEnabled

    controls.previousButton.enabled: !OrchardPlayback.loading && OrchardPlayback.canGoPrevious
    controls.previousButton.tooltip: qsTr("Previous")
    controls.previousButton.onClicked: {
        controls.previousButton.nudgeAnimation.restart();
        OrchardPlayback.previous();
    }

    controls.playButton.enabled: !OrchardPlayback.loading && Boolean(OrchardPlayback.track.id)
    controls.playButton.altShown: OrchardPlayback.playing
    controls.playButton.tooltip: OrchardPlayback.playing ? qsTr("Pause") : qsTr("Play")
    controls.playButton.onClicked: OrchardPlayback.toggle()

    controls.nextButton.enabled: !OrchardPlayback.loading && OrchardPlayback.canGoNext
    controls.nextButton.tooltip: qsTr("Next")
    controls.nextButton.onClicked: {
        controls.nextButton.nudgeAnimation.restart();
        OrchardPlayback.next();
    }

    controls.repeatButton.glyph: OrchardPlayback.repeatMode === "one" ? "repeat-1" : "repeat"
    controls.repeatButton.modeActive: OrchardPlayback.repeatMode !== "off"
    controls.repeatButton.tooltip: OrchardPlayback.repeatMode === "one" ? qsTr("Repeat one")
        : OrchardPlayback.repeatMode === "all" ? qsTr("Repeat all") : qsTr("Repeat off")
    controls.repeatButton.onClicked: OrchardPlayback.cycleRepeatMode()

    controls.lyricsButton.onClicked: root.togglePane("lyrics")
    controls.queueButton.onClicked: root.togglePane("queue")
}
