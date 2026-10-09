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
import QtQuick.Controls
import QtQuick.Layouts

// Names a new private playlist, on YouTube Music or on this computer. A seed
// song is optional: the picker passes one, the sidebar and library do not.
// Created on demand, destroyed on close.
Popup {
    id: root

    property var track: ({})
    // "youtube" or "local". Only a song's own kind can seed a playlist.
    property string source: root.seedIsLocal || OrchardNetwork.offline ? "local" : "youtube"
    readonly property bool hasSeed: Boolean(root.track && root.track.id)
    readonly property bool seedIsLocal: root.hasSeed && String(root.track.id).indexOf("local:") === 0
    readonly property bool local: root.source === "local"
    readonly property bool canCreate: nameField.text.trim().length > 0
        && (root.local || !OrchardLibrary.saving)

    function create(thenAddSongs) {
        if (!root.canCreate)
            return;
        const name = nameField.text;
        if (root.local) {
            const files = root.seedIsLocal ? [root.track.localPath] : [];
            const id = OrchardLocal.createPlaylist(name, files);
            if (thenAddSongs)
                LocalPickers.addSongs(id);
        } else if (root.hasSeed) {
            OrchardLibrary.createPlaylist(root.track, name);
        } else {
            OrchardLibrary.createEmptyPlaylist(name);
        }
        root.close();
    }

    function hint() {
        if (root.local)
            return root.hasSeed ? qsTr("“%1” will be added to it.").arg(root.track.title || qsTr("This song"))
                                : qsTr("Songs stay where they are on your computer. Orchard only remembers the list.");
        return root.hasSeed ? qsTr("“%1” will be added to it. New playlists start private.").arg(root.track.title || qsTr("This song"))
                            : qsTr("Created on YouTube Music, private until you share it.");
    }

    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(400, parent.width - 48)
    padding: 20
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    onOpened: nameField.forceActiveFocus()
    onClosed: root.destroy()

    Overlay.modal: Rectangle {
        color: "#80000000"
    }

    enter: Transition {
        NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 160; easing.type: Easing.OutCubic }
    }

    exit: Transition {
        NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 120; easing.type: Easing.InCubic }
    }

    background: Rectangle {
        color: "#181b1e"
        radius: 14
        border.color: "#35ffffff"
        border.width: 1
    }

    // One half of the source switch. A song can only live in its own kind of playlist.
    component SourceChoice: Button {
        id: choice

        property string value
        readonly property bool chosen: root.source === value

        Layout.fillWidth: true
        implicitHeight: 34
        enabled: (!root.hasSeed || root.seedIsLocal === (value === "local")) && (value === "local" || !OrchardNetwork.offline)
        onClicked: root.source = value
        contentItem: Text {
            text: choice.text
            color: choice.chosen ? "#10120e" : choice.enabled ? "#d6d9d4" : "#5c625d"
            font.family: "Inter"
            font.pixelSize: 12
            font.weight: Font.DemiBold
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
        }
        background: Rectangle {
            radius: 8
            color: choice.chosen ? "#8cc5a5" : choice.hovered && choice.enabled ? "#1effffff" : "#14ffffff"
        }
    }

    contentItem: ColumnLayout {
        spacing: 14

        Text {
            text: qsTr("New playlist")
            color: "#f2f0eb"
            font.family: "Inter"
            font.pixelSize: 17
            font.weight: Font.DemiBold
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 6

            SourceChoice { text: qsTr("YouTube Music"); value: "youtube" }
            SourceChoice { text: qsTr("On this computer"); value: "local" }
        }

        Text {
            Layout.fillWidth: true
            text: root.hint()
            color: "#a6adb2"
            font.family: "Inter"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        TextField {
            id: nameField

            Layout.fillWidth: true
            implicitHeight: 40
            leftPadding: 14
            rightPadding: 14
            selectByMouse: true
            maximumLength: 150
            placeholderText: qsTr("Playlist name")
            placeholderTextColor: "#727970"
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 13
            onAccepted: root.create(false)
            background: Rectangle {
                radius: 10
                color: "#1b201c"
                border.color: nameField.activeFocus ? "#8cc5a5" : "#3a413b"
            }
        }

        RowLayout {
            Layout.alignment: Qt.AlignRight
            spacing: 8

            Button {
                id: cancelButton

                text: qsTr("Cancel")
                flat: true
                onClicked: root.close()
                contentItem: Text {
                    text: cancelButton.text
                    color: "#d6d9d4"
                    font.family: "Inter"
                    font.pixelSize: 13
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    implicitWidth: 84
                    implicitHeight: 34
                    radius: 8
                    color: cancelButton.hovered ? "#1effffff" : "transparent"
                }
            }

            // Local playlists usually start from a pile of files, so offer to pick them straight away.
            Button {
                id: withSongsButton

                visible: root.local && !root.hasSeed
                text: qsTr("Create and add songs…")
                enabled: root.canCreate
                onClicked: root.create(true)
                contentItem: Text {
                    text: withSongsButton.text
                    color: withSongsButton.enabled ? "#d6d9d4" : "#6b716c"
                    font.family: "Inter"
                    font.pixelSize: 13
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    implicitHeight: 34
                    implicitWidth: 168
                    radius: 8
                    color: withSongsButton.hovered && withSongsButton.enabled ? "#1effffff" : "#14ffffff"
                }
            }

            Button {
                id: createButton

                text: qsTr("Create")
                enabled: root.canCreate
                onClicked: root.create(false)
                contentItem: Text {
                    text: createButton.text
                    color: createButton.enabled ? "#10120e" : "#6b716c"
                    font.family: "Inter"
                    font.pixelSize: 13
                    font.weight: Font.DemiBold
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    implicitWidth: 84
                    implicitHeight: 34
                    radius: 8
                    color: !createButton.enabled ? "#2a302c" : createButton.hovered ? "#a3d6b8" : "#8cc5a5"
                }
            }
        }
    }
}
