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
import QtQuick
import QtQuick.Effects

// Synced lyrics for the playing track, with per-word fill when the provider has timings.
Item {
    id: root

    property color accentColor: "#f0eee7"
    property color primaryText: "#f5f3ee"
    property color mutedText: "#858a82"
    property bool running: visible

    readonly property var lines: OrchardLyrics.lines || []
    readonly property bool synced: OrchardLyrics.mode === "synced"
    property int pixelSize: 21
    // A line stays lit until the next one starts; providers often end lines before the last word trails off.
    // Read lines here so a new track refreshes the highlight even at the same clock value.
    readonly property int currentIndex: synced && lines.length ? OrchardLyrics.indexAt(clock) : -1
    readonly property int pauseIndex: synced ? pauseAt(clock) : -2
    readonly property real wordClock: clock + 0.08
    readonly property int focusIndex: pauseIndex >= 0 ? pauseIndex : currentIndex
    // Browsing by hand shows every line crisp.
    readonly property bool depthActive: synced && following
    readonly property bool fadeDepth: OrchardAppearance.lyricsDistanceFade
    readonly property bool blurDepth: OrchardAppearance.lyricsFocusBlur
    readonly property real columnWidth: column.width
    readonly property real pauseProgress: {
        if (pauseIndex < -1)
            return -1;
        const span = pauseWindow(pauseIndex);
        return span ? Math.max(0, Math.min(1, (clock - span.start) / (span.end - span.start))) : -1;
    }
    signal glide(real delta)
    signal glideStopped
    readonly property color fillColor: Qt.rgba(accentColor.r * 0.4 + primaryText.r * 0.6,
                                               accentColor.g * 0.4 + primaryText.g * 0.6,
                                               accentColor.b * 0.4 + primaryText.b * 0.6, 1)

    // Player position only ticks every few frames; this clock fills the gaps so wipes glide.
    property real clock: 0
    // Follows the incoming song once the fullscreen view swaps to it mid-mix.
    property bool incoming: false
    readonly property real reported: incoming ? OrchardPlayback.transitionPosition : OrchardPlayback.audiblePosition
    property bool following: true

    function tint(color, alpha) {
        return Qt.rgba(color.r, color.g, color.b, alpha);
    }

    // Instrumental gap after a line, or null. -1 stands for the intro before the first line.
    function pauseWindow(index) {
        const next = lines[index + 1];
        if (!next)
            return null;
        if (index < 0) {
            const first = Number(next.startTime || 0);
            return first >= 7 ? { start: 0.5, end: first } : null;
        }
        const line = lines[index];
        const start = Number(line.startTime || 0);
        const nextStart = Number(next.startTime || 0);
        if (nextStart - start < 7)
            return null;
        const words = line.words || [];
        let end = Number(line.endTime || 0);
        if (!(end > start) && words.length)
            end = Number(words[words.length - 1].endTime || 0);
        const accurate = end > start;
        const pauseStart = (accurate ? end : start) + (accurate ? 0.4 : 2.4);
        return pauseStart < nextStart ? { start: pauseStart, end: nextStart } : null;
    }

    function pauseAt(time) {
        const index = OrchardLyrics.indexAt(time);
        const window = pauseWindow(index);
        return window && time >= window.start && time < window.end ? index : -2;
    }

    // Untyped on purpose: compiled bindings over C++ lists have bitten qmlcachegen before.
    function translationAt(index) {
        const list = OrchardTranslation.lines;
        return list && list.length === lines.length ? String(list[index] || "") : "";
    }

    function scrollToCurrent(animated) {
        if (!following || !synced)
            return;
        const target = pauseIndex === -1 ? introPause
                     : repeater.itemAt(Math.max(0, pauseIndex >= 0 ? pauseIndex : currentIndex));
        if (!target)
            return;
        // Pause dots sit at the bottom of their line; centre on them, not the lyric above.
        const anchorY = pauseIndex >= 0 ? target.y + target.height - 20 : target.y + target.height / 2;
        const maxY = Math.max(0, flick.contentHeight - flick.height);
        const y = Math.max(0, Math.min(maxY, anchorY - flick.height / 2));
        scroll.stop();
        if (animated && !fresh && OrchardAppearance.lyricsStaggeredScroll) {
            const delta = y - flick.contentY;
            flick.contentY = y;
            // Lines start where they were and catch up one after another.
            glide(delta);
        } else if (animated && !fresh) {
            scroll.to = y;
            scroll.start();
        } else {
            flick.contentY = y;
            glideStopped();
        }
    }

    onIncomingChanged: {
        OrchardLyrics.followIncoming = incoming;
        resync();
    }
    Component.onDestruction: if (incoming) OrchardLyrics.followIncoming = false

    function resync() {
        clock = reported;
        following = true;
        Qt.callLater(scrollToCurrent, false);
    }

    // Lines load asynchronously; the view stays hidden until every one is laid out
    // and scrolled to, so it never opens at the top and then lurches.
    property bool built: true
    // Late layout shifts right after a rebuild snap instead of gliding.
    property bool fresh: false
    function checkBuilt() {
        for (let i = 0; i < repeater.count; ++i) {
            const item = repeater.itemAt(i);
            if (!item || item.status !== Loader.Ready)
                return;
        }
        if (!built)
            settle.restart();
    }

    // Positioners lay out on the next polish; give them a couple of frames.
    Timer {
        id: settle
        interval: 80
        onTriggered: {
            root.scrollToCurrent(false);
            root.built = true;
            root.fresh = true;
            freshTimer.restart();
        }
    }

    Timer {
        id: freshTimer
        interval: 700
        onTriggered: root.fresh = false
    }

    onReportedChanged: {
        // Snap on seeks and pauses; otherwise ease toward the player so ticks never cause visible jumps.
        if (!OrchardPlayback.playing || Math.abs(reported - clock) > 0.35)
            clock = reported;
        else
            clock += (reported - clock) * 0.25;
    }
    onCurrentIndexChanged: scrollToCurrent(true)
    onPauseIndexChanged: Qt.callLater(scrollToCurrent, true)
    onLinesChanged: {
        built = false;
        settle.stop();
        resync();
        Qt.callLater(checkBuilt);
    }
    onVisibleChanged: if (visible) resync()
    onHeightChanged: Qt.callLater(scrollToCurrent, false)

    FrameAnimation {
        running: root.running && root.synced && OrchardPlayback.playing
        onTriggered: root.clock += frameTime
    }

    // Give the reader a few seconds after a manual scroll before snapping back.
    Timer {
        id: resumeFollow
        interval: 3000
        onTriggered: {
            root.following = true;
            root.scrollToCurrent(true);
        }
    }

    FontMetrics {
        id: mainMetrics
        font.family: "Inter"
        font.pixelSize: root.pixelSize
        font.weight: Font.Bold
        font.letterSpacing: -0.4
    }

    FontMetrics {
        id: adlibMetrics
        font.family: "Inter"
        font.pixelSize: Math.round(root.pixelSize * 0.68)
        font.weight: Font.Medium
        font.letterSpacing: -0.4
    }

    // Top and bottom fade, so lines dissolve into the glass instead of hitting a hard edge.
    Item {
        id: edgeMask
        anchors.fill: flick
        visible: false
        layer.enabled: true

        Rectangle {
            anchors.fill: parent
            gradient: Gradient {
                GradientStop { position: 0; color: "transparent" }
                GradientStop { position: 0.12; color: "white" }
                GradientStop { position: 0.86; color: "white" }
                GradientStop { position: 1; color: "transparent" }
            }
        }
    }

    Flickable {
        id: flick
        anchors.fill: parent
        anchors.topMargin: sourceBar.visible ? sourceBar.height + 8 : 0
        visible: OrchardLyrics.status === "ready"
        opacity: root.built ? 1 : 0
        Behavior on opacity { enabled: root.built; NumberAnimation { duration: 220 } }
        contentWidth: width
        contentHeight: column.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        layer.enabled: true
        layer.effect: MultiEffect {
            maskEnabled: true
            maskSource: edgeMask
            maskThresholdMin: 0.5
            maskSpreadAtMin: 1
        }

        onMovementStarted: {
            scroll.stop();
            root.glideStopped();
            root.following = false;
            resumeFollow.stop();
        }
        onMovementEnded: if (root.synced) resumeFollow.restart()

        NumberAnimation {
            id: scroll
            target: flick
            property: "contentY"
            duration: 650
            easing.type: Easing.OutCubic
        }

        Column {
            id: column
            width: flick.width
            // While hidden, follow the layout as it settles.
            onImplicitHeightChanged: if (!root.built) root.scrollToCurrent(false)
            // Synced lyrics start near the middle so the first line can be centred like the rest.
            topPadding: root.synced ? flick.height * 0.34 : 16
            bottomPadding: root.synced ? flick.height * 0.5 : 24

            Row {
                visible: !root.synced
                spacing: 8
                bottomPadding: 12

                LucideIcon {
                    width: 14
                    height: 14
                    name: "info"
                    color: root.mutedText
                }

                Text {
                    text: qsTr("These lyrics aren't synced to the music.")
                    color: root.mutedText
                    font.family: "Inter"
                    font.pixelSize: 11
                }
            }

            LyricsPauseDots {
                id: introPause
                color: root.fillColor
                running: root.running
                x: root.lines.length && root.lines[0].agentLane === "alternate" ? column.width - implicitWidth : 0
                active: root.pauseIndex === -1
                progress: active ? root.pauseProgress : -1
            }

            Repeater {
                id: repeater
                model: root.lines

                // Word-timed lyrics run to thousands of items. Building a slice per frame
                // keeps a new song's lyrics from freezing the UI mid-transition.
                delegate: Loader {
                    id: lineLoader
                    required property var modelData
                    required property int index
                    width: column.width
                    asynchronous: true
                    onLoaded: Qt.callLater(root.checkBuilt)

                    sourceComponent: LyricsLine {
                        view: root
                        modelData: lineLoader.modelData
                        index: lineLoader.index
                    }
                }
            }

            Text {
                width: column.width
                topPadding: 18
                visible: Boolean(OrchardLyrics.source)
                text: qsTr("Lyrics from %1").arg(sourceBar.sourceLabel(OrchardLyrics.source))
                color: root.mutedText
                font.family: "Inter"
                font.pixelSize: 10
            }
        }
    }

    LyricsSourceBar {
        id: sourceBar
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.topMargin: 4
        anchors.rightMargin: 4
        width: parent.width - 8
        visible: Boolean(OrchardLyrics.trackId)
        accentColor: root.accentColor
        primaryText: root.primaryText
    }

    // Loading, missing, and idle all share one quiet placeholder.
    LyricsPlaceholder {
        anchors.centerIn: parent
        width: parent.width - 28
        accentColor: root.accentColor
        primaryText: root.primaryText
        dotColor: root.fillColor
        running: root.running
    }
}
