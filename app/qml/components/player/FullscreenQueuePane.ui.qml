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
import QtQuick.Controls
import QtQuick.Layouts

// Up next list with queue actions and the sleep timer.
ColumnLayout {
    id: pane

    property var queueItems: [
        { title: "Midnight City", artist: "M83", duration: "4:03" },
        { title: "Intro", artist: "The xx", duration: "2:07" },
        { title: "Genesis", artist: "Grimes", duration: "4:15" }
    ]
    property bool autoplayEnabled: true
    property real paneSwap: 1
    property color accentColor: "#f0eee7"
    property color primaryText: "#f7f5f0"
    property color secondaryText: "#c3c6bf"
    property color mutedText: "#8d928a"
    property alias list: upNext
    property alias clearButton: clearButton

    spacing: 14

    RowLayout {
        Layout.fillWidth: true
        spacing: 8

        Text {
            Layout.fillWidth: true
            text: qsTr("Up next")
            color: pane.primaryText
            font.family: "Inter"
            font.pixelSize: 28
            font.weight: Font.Bold
            font.letterSpacing: -0.6
        }

        SleepTimerButton {
            accentColor: pane.accentColor
            primaryText: pane.primaryText
            secondaryText: pane.secondaryText
            mutedText: pane.mutedText
        }
    }

    RowLayout {
        Layout.fillWidth: true
        spacing: 8

        QueueBestMixButton {
            panel: pane
            count: upNext.count
            Layout.maximumWidth: Math.max(0, pane.width - clearButton.implicitWidth - 8)
        }

        Item { Layout.fillWidth: true }

        Button {
            id: clearButton
            objectName: "clearFullscreenQueue"
            text: qsTr("Clear")
            enabled: upNext.count > 0
            implicitHeight: 28
            leftPadding: 10
            rightPadding: 10
            Accessible.name: qsTr("Clear queue")

            background: Rectangle {
                radius: height / 2
                color: clearButton.down ? "#26ffffff" : clearButton.hovered ? "#1affffff" : "transparent"
                border.color: clearButton.activeFocus ? pane.accentColor : "transparent"
            }

            contentItem: Text {
                text: clearButton.text
                color: clearButton.hovered ? pane.primaryText : pane.secondaryText
                opacity: clearButton.enabled ? 1 : 0.45
                font.family: "Inter"
                font.pixelSize: 11
                font.bold: true
                verticalAlignment: Text.AlignVCenter
            }
        }
    }

    Text {
        Layout.fillWidth: true
        visible: Boolean(OrchardPlayback.bestMix.error)
        text: OrchardPlayback.bestMix.error
        color: "#e8c29b"
        font.family: "Inter"
        font.pixelSize: 11
        wrapMode: Text.Wrap
    }

    ListView {
        id: upNext
        Layout.fillWidth: true
        Layout.fillHeight: true
        clip: true
        spacing: 4
        model: pane.queueItems
        boundsBehavior: Flickable.StopAtBounds

        ScrollBar.vertical: ScrollBar {
            id: upNextBar
            policy: upNext.contentHeight > upNext.height ? ScrollBar.AsNeeded : ScrollBar.AlwaysOff
            contentItem: Rectangle {
                implicitWidth: 4
                radius: 2
                color: upNextBar.pressed ? "#80ffffff" : "#30ffffff"
            }
        }

        // The logic layer binds playback and removal for each row.
        delegate: FullscreenQueueRow {
            required property var modelData
            required property int index
            width: upNext.width - 8
            track: modelData
            rowIndex: index
            paneSwap: pane.paneSwap
            accentColor: pane.accentColor
            primaryText: pane.primaryText
            mutedText: pane.mutedText
        }
    }

    Text {
        Layout.fillWidth: true
        Layout.fillHeight: true
        visible: upNext.count === 0
        text: pane.autoplayEnabled ? qsTr("Nothing queued. Autoplay has this covered.") : qsTr("Nothing queued.")
        color: pane.mutedText
        font.family: "Inter"
        font.pixelSize: 14
    }
}
