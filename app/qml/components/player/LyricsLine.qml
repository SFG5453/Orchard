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

// One lyric line: word fill, depth blur and fade, staggered glide, pause dots.
Item {
    id: line

    required property var view
    required property var modelData
    required property int index

    // Pause dots appear under the line without dimming it.
    readonly property bool current: index === view.currentIndex
    readonly property int depth: view.depthActive ? Math.abs(index - view.focusIndex) : 0
    readonly property bool lifted: hover.containsMouse
    readonly property real fadeTarget: view.fadeDepth && depth > 0 && !lifted ? Math.max(0.55, 1 - depth * 0.14) : 1
    // Layers past the viewport would only cost texture memory.
    readonly property real blurTarget: view.blurDepth && depth > 0 && depth <= 8 && !lifted ? Math.min(1, depth * 0.2) : 0
    property real fade: fadeTarget
    property real blur: blurTarget
    property real glideOffset: 0
    Behavior on fade { NumberAnimation { duration: 380; easing.type: Easing.OutCubic } }
    Behavior on blur { NumberAnimation { duration: 380; easing.type: Easing.OutCubic } }
    readonly property bool alternate: modelData.agentLane === "alternate"
    readonly property var words: modelData.words || []
    readonly property var adlibs: modelData.adlibs || []
    readonly property string translation: view.translationAt(index)
    readonly property bool translated: translation.length > 0
    readonly property color restColor: view.tint(view.primaryText, !view.synced ? 0.72 : hover.containsMouse ? 0.68 : 0.24)

    readonly property var wordEnds: words.map((_, i) => wordEnd(words, i))
    readonly property var adlibEnds: adlibs.map((_, i) => wordEnd(adlibs, i))

    function wordEnd(list, i) {
        const entry = list[i];
        const start = Number(entry.startTime || 0);
        const end = Number(entry.endTime || 0);
        if (end > start)
            return end;
        const next = list[i + 1];
        const nextStart = next ? Number(next.startTime || 0) : 0;
        return nextStart > start ? nextStart : start + 0.4;
    }

    width: view.columnWidth
    height: body.implicitHeight + 22 + pause.height
    transform: Translate { y: line.glideOffset }

    // Each line trails the one above it so a scroll reads as a cascade.
    SequentialAnimation {
        id: glideRun
        PauseAnimation { id: glideDelay }
        NumberAnimation {
            target: line
            property: "glideOffset"
            to: 0
            duration: 760
            easing.type: Easing.OutBack
            easing.overshoot: 0.5
        }
    }

    Connections {
        target: line.view
        function onGlide(delta) {
            const ahead = line.index - line.view.focusIndex;
            if (Math.abs(ahead) > 10)
                return;
            glideRun.stop();
            line.glideOffset += delta;
            glideDelay.duration = ahead > 0 ? Math.min(ahead, 8) * 45 : 0;
            glideRun.start();
        }
        function onGlideStopped() {
            glideRun.stop();
            line.glideOffset = 0;
        }
    }

    Item {
        id: body
        y: 11
        width: parent.width
        opacity: line.fade
        implicitHeight: lineColumn.implicitHeight
        height: implicitHeight
        scale: line.current ? 1.01 : 1
        transformOrigin: line.alternate ? Item.Right : Item.Left
        transform: Translate {
            x: hover.containsMouse && !line.current && view.synced ? (line.alternate ? -6 : 6) : 0
            Behavior on x { NumberAnimation { duration: 240; easing.type: Easing.OutCubic } }
        }
        Behavior on scale { NumberAnimation { duration: 240; easing.type: Easing.OutCubic } }

        layer.enabled: line.current || line.blur > 0.01
        layer.effect: MultiEffect {
            blurEnabled: line.blur > 0.01
            blur: line.blur
            blurMax: 16
            shadowEnabled: line.current
            shadowColor: view.accentColor
            shadowOpacity: 0.4
            shadowBlur: 1
            shadowHorizontalOffset: 0
            shadowVerticalOffset: 0
        }

        Column {
            id: lineColumn
            x: line.alternate ? 34 : 0
            width: parent.width - 34
            spacing: 4

            // Translated lines lead with the English; the original shrinks below like an adlib.
            Text {
                width: parent.width
                visible: line.words.length === 0 || line.translated
                text: line.translated ? line.translation : line.modelData.text || ""
                color: line.current ? view.primaryText : line.restColor
                horizontalAlignment: line.alternate ? Text.AlignRight : Text.AlignLeft
                font.family: "Inter"
                font.pixelSize: view.synced ? view.pixelSize : 16
                font.weight: view.synced ? Font.Bold : Font.Medium
                font.letterSpacing: view.synced ? -0.4 : 0
                lineHeight: 1.12
                wrapMode: Text.Wrap
                Behavior on color { ColorAnimation { duration: 240 } }
            }

            LyricsWordRows {
                width: parent.width
                visible: line.words.length > 0
                words: line.words
                ends: line.wordEnds
                lit: line.current
                metrics: line.translated ? adlibMetrics : mainMetrics
                gap: metrics.font.pixelSize * (line.translated ? 0.24 : 0.26)
                pixelSize: metrics.font.pixelSize
                weight: line.translated ? Font.Medium : Font.Bold
                alignRight: line.alternate
                baseColor: line.translated ? view.tint(view.primaryText, line.current ? 0.48 : 0.2)
                         : line.current ? view.tint(view.primaryText, 0.28) : line.restColor
                fillColor: line.translated ? view.tint(view.accentColor, 0.9) : view.fillColor
                doneColor: view.primaryText
                clockSource: root
            }

            Text {
                width: parent.width
                visible: line.translated && line.words.length === 0
                text: line.modelData.text || ""
                color: view.tint(view.primaryText, line.current ? 0.48 : 0.2)
                horizontalAlignment: line.alternate ? Text.AlignRight : Text.AlignLeft
                font: adlibMetrics.font
                wrapMode: Text.Wrap
            }

            // Background vocals trail the main line in a smaller voice.
            LyricsWordRows {
                width: parent.width
                visible: line.adlibs.length > 0
                words: line.adlibs
                ends: line.adlibEnds
                lit: line.current
                metrics: adlibMetrics
                gap: adlibMetrics.font.pixelSize * 0.24
                pixelSize: adlibMetrics.font.pixelSize
                weight: Font.Medium
                alignRight: line.alternate
                baseColor: view.tint(view.primaryText, line.current ? 0.48 : 0.2)
                fillColor: view.tint(view.accentColor, 0.9)
                doneColor: view.primaryText
                clockSource: root
            }
        }
    }

    LyricsPauseDots {
        id: pause
        color: view.fillColor
        running: view.running
        x: line.alternate ? parent.width - implicitWidth : 0
        y: body.y + body.height + 11
        active: view.pauseIndex === line.index
        progress: active ? view.pauseProgress : -1
    }

    MouseArea {
        id: hover
        anchors.fill: body
        hoverEnabled: view.synced
        enabled: view.synced
        cursorShape: enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
        onClicked: {
            // Same 5s runway before the mix as the fullscreen scrubber.
            const start = Number(line.modelData.startTime || 0);
            const mix = OrchardPlayback.mixStart;
            OrchardPlayback.seek(mix >= 0 ? Math.min(start, Math.max(0, mix - 5, OrchardPlayback.position)) : start);
            view.following = true;
        }
    }
}
