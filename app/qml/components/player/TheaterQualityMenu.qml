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
import QtQuick.Controls

// Height cap, FSR 1 upscaling and its sharpening for the music video picture.
Menu {
    id: root

    property color accentColor: "#f0eee7"
    // Heights the current video offers; a fixed ladder until the first resolve reports them.
    readonly property var heightOptions: (OrchardMusicVideo.heights || []).length
        ? OrchardMusicVideo.heights : [2160, 1440, 1080, 720, 480, 360]

    function heightLabel(height) {
        return height >= 2160 ? qsTr("4K") : height + "p";
    }

    component QualityItem: MenuItem {
        id: item

        implicitWidth: 236
        implicitHeight: 36
        checkable: true
        // The row draws its own check mark.
        indicator: null

        contentItem: Row {
            spacing: 10
            leftPadding: 4

            LucideIcon {
                anchors.verticalCenter: parent.verticalCenter
                name: "check"
                color: "#f0eee7"
                width: 14
                height: 14
                opacity: item.checked ? 1 : 0
            }

            Label {
                anchors.verticalCenter: parent.verticalCenter
                text: item.text
                color: "#f7f5f0"
                font.pixelSize: 13
            }
        }

        background: Rectangle {
            radius: 8
            color: item.highlighted ? "#1fffffff" : "transparent"
        }
    }

    padding: 6

    background: Rectangle {
        implicitWidth: 248
        radius: 12
        color: Qt.alpha(OrchardAppearance.popupColor, 0.93)
        border.color: "#1fffffff"
    }

    QualityItem {
        text: qsTr("Best available")
        checked: OrchardMusicVideo.maxHeight === 0
        onTriggered: OrchardMusicVideo.maxHeight = 0
    }

    Repeater {
        model: root.heightOptions

        QualityItem {
            required property int modelData

            text: root.heightLabel(modelData)
            checked: OrchardMusicVideo.maxHeight === modelData
            onTriggered: OrchardMusicVideo.maxHeight = modelData
        }
    }

    Label {
        width: 236
        visible: OrchardMusicVideo.qualityNote !== ""
        height: visible ? implicitHeight : 0
        leftPadding: 10
        rightPadding: 10
        topPadding: 4
        bottomPadding: 6
        text: OrchardMusicVideo.qualityNote
        wrapMode: Text.Wrap
        color: "#a4a9ad"
        font.pixelSize: 12
    }

    MenuSeparator {
        contentItem: Rectangle {
            implicitHeight: 1
            color: "#1fffffff"
        }
    }

    QualityItem {
        text: qsTr("FSR 1 upscaling")
        checked: OrchardMusicVideo.upscaling
        onTriggered: OrchardMusicVideo.upscaling = checked
    }

    Item {
        width: 236
        height: 44
        enabled: OrchardMusicVideo.upscaling
        opacity: enabled ? 1 : 0.45

        Label {
            id: sharpnessLabel

            x: 10
            anchors.verticalCenter: parent.verticalCenter
            text: qsTr("Sharpness")
            color: "#f7f5f0"
            font.pixelSize: 13
        }

        Slider {
            id: sharpness

            anchors.left: sharpnessLabel.right
            anchors.leftMargin: 12
            anchors.right: parent.right
            anchors.rightMargin: 10
            anchors.verticalCenter: parent.verticalCenter
            from: 0
            to: 1
            value: OrchardMusicVideo.sharpness
            Accessible.name: qsTr("Sharpness")
            onMoved: OrchardMusicVideo.sharpness = value

            background: Rectangle {
                x: sharpness.leftPadding
                y: sharpness.topPadding + sharpness.availableHeight / 2 - height / 2
                width: sharpness.availableWidth
                height: 3
                radius: 1.5
                color: "#2effffff"

                Rectangle {
                    width: sharpness.visualPosition * parent.width
                    height: parent.height
                    radius: parent.radius
                    color: root.accentColor
                }
            }

            handle: Rectangle {
                x: sharpness.leftPadding + sharpness.visualPosition * (sharpness.availableWidth - width)
                y: sharpness.topPadding + sharpness.availableHeight / 2 - height / 2
                width: 12
                height: 12
                radius: 6
                color: "#f7f5f0"
            }
        }
    }
}
