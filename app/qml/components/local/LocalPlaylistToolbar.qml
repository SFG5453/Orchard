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
import ".."

// Edit actions for a playlist kept on this computer: songs, cover, name, delete.
Flow {
    id: root

    // The playlist detail map from OrchardPlaylist.
    property var playlist: ({})
    property color accentColor: "#7fbe90"
    property color accentSoftColor: "#96caa4"

    readonly property string playlistId: playlist.id || ""

    signal deleted

    spacing: 8

    component Action: Button {
        id: action

        property string glyph

        implicitHeight: 32
        implicitWidth: row.implicitWidth + 26
        hoverEnabled: true
        contentItem: Row {
            id: row
            spacing: 7
            leftPadding: 2

            LucideIcon {
                anchors.verticalCenter: parent.verticalCenter
                width: 14
                height: 14
                name: action.glyph
                color: root.accentSoftColor
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: action.text
                color: "#e6e9e5"
                font.family: "Inter"
                font.pixelSize: 12
            }
        }
        background: Rectangle {
            radius: 16
            color: action.hovered ? "#22ffffff" : "#0effffff"
            border.color: action.activeFocus ? root.accentSoftColor : "#18ffffff"
        }
    }

    Action { glyph: "folder-plus"; text: qsTr("Add songs"); onClicked: LocalPickers.addSongs(root.playlistId) }
    Action { glyph: "folder-plus"; text: qsTr("Add folder"); onClicked: LocalPickers.addFolder(root.playlistId) }
    Action { glyph: "image"; text: qsTr("Cover"); onClicked: LocalPickers.pickCover("playlist", root.playlistId) }
    Action {
        visible: Boolean(root.playlist.hasCustomCover)
        glyph: "x"
        text: qsTr("Auto cover")
        onClicked: OrchardLocal.clearPlaylistCover(root.playlistId)
    }
    Action { glyph: "pencil"; text: qsTr("Rename"); onClicked: renamePopup.openWith(root.playlist.title || "") }
    Action { glyph: "trash-2"; text: qsTr("Delete"); onClicked: deletePopup.open() }

    // One small popup for renaming. A playlist called "New playlist (2)" deserves better.
    Popup {
        id: renamePopup

        function openWith(title) {
            nameField.text = title;
            open();
        }

        parent: Overlay.overlay
        anchors.centerIn: parent
        width: Math.min(360, parent.width - 48)
        padding: 18
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        onOpened: {
            nameField.forceActiveFocus();
            nameField.selectAll();
        }
        Overlay.modal: Rectangle { color: "#80000000" }
        background: Rectangle { color: "#181b1e"; radius: 14; border.color: "#35ffffff" }

        contentItem: ColumnLayout {
            spacing: 12

            Text {
                text: qsTr("Rename playlist")
                color: "#f2f0eb"
                font.family: "Inter"
                font.pixelSize: 16
                font.weight: Font.DemiBold
            }
            TextField {
                id: nameField

                Layout.fillWidth: true
                implicitHeight: 38
                maximumLength: 150
                selectByMouse: true
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 13
                onAccepted: {
                    OrchardLocal.renamePlaylist(root.playlistId, text);
                    renamePopup.close();
                }
                background: Rectangle {
                    radius: 10
                    color: "#1b201c"
                    border.color: nameField.activeFocus ? "#8cc5a5" : "#3a413b"
                }
            }
        }
    }

    Popup {
        id: deletePopup

        parent: Overlay.overlay
        anchors.centerIn: parent
        width: Math.min(360, parent.width - 48)
        padding: 18
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        Overlay.modal: Rectangle { color: "#80000000" }
        background: Rectangle { color: "#181b1e"; radius: 14; border.color: "#35ffffff" }

        contentItem: ColumnLayout {
            spacing: 12

            Text {
                Layout.fillWidth: true
                text: qsTr("Delete “%1”?").arg(root.playlist.title || "")
                color: "#f2f0eb"
                font.family: "Inter"
                font.pixelSize: 16
                font.weight: Font.DemiBold
                wrapMode: Text.WordWrap
            }
            Text {
                Layout.fillWidth: true
                text: qsTr("Your songs stay on your computer. Only the playlist goes away.")
                color: "#a6adb2"
                font.family: "Inter"
                font.pixelSize: 12
                wrapMode: Text.WordWrap
            }
            RowLayout {
                Layout.alignment: Qt.AlignRight
                spacing: 8

                Button {
                    text: qsTr("Cancel")
                    flat: true
                    onClicked: deletePopup.close()
                }
                Button {
                    text: qsTr("Delete")
                    onClicked: {
                        deletePopup.close();
                        OrchardLocal.deletePlaylist(root.playlistId);
                        root.deleted();
                    }
                }
            }
        }
    }
}
