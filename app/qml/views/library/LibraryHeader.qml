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

pragma ComponentBehavior: Bound

import "../../components"
import Orchard
import QtQuick
import QtQuick.Controls

// Title, item summary, filter field, Refresh and category pills.
Column {
    id: root

    // Entries are { key, title }; "All" is added here.
    property var categories: []
    // Loaded item count per category key.
    property var counts: ({})
    property string filter: "all"
    property string summary
    property bool loading: false
    property alias query: filterField.text

    signal filterSelected(string key)
    signal refreshRequested

    function countLabel(key) {
        const count = root.counts[key];
        return count > 0 ? count.toLocaleString(Qt.locale(), "f", 0) : "";
    }

    function focusFilter() {
        filterField.forceActiveFocus();
    }

    spacing: 18

    Item {
        width: parent.width
        height: 52

        Column {
            anchors.left: parent.left
            anchors.right: tools.left
            anchors.rightMargin: 16
            anchors.verticalCenter: parent.verticalCenter
            spacing: 4

            Text {
                width: parent.width
                text: qsTr("Your library")
                color: "#f2eee7"
                font.family: "Inter"
                font.pixelSize: 26
                font.weight: Font.DemiBold
                elide: Text.ElideRight
            }
            Text {
                width: parent.width
                visible: root.summary !== ""
                text: root.summary
                color: "#a4adb1"
                font.family: "Inter"
                font.pixelSize: 13
                elide: Text.ElideRight
            }
        }

        Row {
            id: tools
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 2
            spacing: 8

            Rectangle {
                width: root.width < 720 ? 130 : 190
                height: 32
                radius: 16
                color: filterField.activeFocus ? "#14ffffff" : "#08ffffff"
                border.color: filterField.activeFocus ? "#b8d4bd" : "#18ffffff"
                Behavior on color { ColorAnimation { duration: Motion.normal } }

                LucideIcon {
                    x: 12
                    anchors.verticalCenter: parent.verticalCenter
                    width: 14
                    height: 14
                    name: "search"
                    color: "#a4adb1"
                }

                TextField {
                    id: filterField
                    anchors.fill: parent
                    leftPadding: 33
                    rightPadding: 30
                    placeholderText: qsTr("Filter")
                    placeholderTextColor: "#8d968e"
                    color: "#f2eee7"
                    font.family: "Inter"
                    font.pixelSize: 13
                    Accessible.name: qsTr("Filter your library")
                    Keys.onEscapePressed: clear()
                    background: Item {}
                }

                Button {
                    id: clearButton
                    anchors.right: parent.right
                    anchors.rightMargin: 4
                    anchors.verticalCenter: parent.verticalCenter
                    width: 24
                    height: 24
                    visible: filterField.text.length > 0
                    Accessible.name: qsTr("Clear filter")
                    onClicked: filterField.clear()
                    background: Rectangle {
                        radius: 12
                        color: clearButton.hovered || clearButton.activeFocus ? "#20ffffff" : "transparent"
                    }
                    contentItem: LucideIcon {
                        name: "x"
                        color: "#d6d9d3"
                    }
                }
            }

            // YouTube Music or this computer, chosen in the dialog.
            LibraryPill {
                text: root.width < 720 ? "" : qsTr("New playlist")
                iconName: "plus"
                Accessible.name: qsTr("New playlist")
                onClicked: newPlaylist.createObject(Overlay.overlay).open()
            }

            // Its own button: files on this computer never touch YouTube Music.
            LibraryPill {
                id: localPill
                text: root.width < 720 ? "" : qsTr("Add local files")
                iconName: "folder-plus"
                busy: OrchardLocal.busy
                Accessible.name: qsTr("Add local files")
                onClicked: localMenu.popup(localPill, 0, localPill.height + 6)
            }

            LibraryPill {
                text: qsTr("Refresh")
                iconName: "refresh-cw"
                busy: root.loading
                enabled: !root.loading
                onClicked: root.refreshRequested()
            }
        }
    }

    Menu {
        id: localMenu

        width: 220
        padding: 5
        background: Rectangle { color: "#242c27"; radius: 10; border.color: "#455348" }

        component Entry: MenuItem {
            id: entry
            implicitHeight: 36
            background: Rectangle {
                radius: 6
                color: entry.highlighted ? "#435247" : "#00435247"
            }
            contentItem: Text {
                text: entry.text
                color: "#f2f0eb"
                font.family: "Inter"
                font.pixelSize: 13
                verticalAlignment: Text.AlignVCenter
                elide: Text.ElideRight
            }
        }

        Entry { text: qsTr("Add songs…"); onTriggered: LocalPickers.addSongs("") }
        Entry { text: qsTr("Add a folder…"); onTriggered: LocalPickers.addFolder("") }
    }

    Component {
        id: newPlaylist

        NewPlaylistDialog {}
    }

    Flow {
        width: parent.width
        spacing: 8

        Repeater {
            model: [{ key: "all", title: qsTr("All") }].concat(root.categories)

            LibraryPill {
                required property var modelData

                text: modelData.title
                detail: root.countLabel(modelData.key)
                checked: root.filter === modelData.key
                checkable: true
                autoExclusive: true
                onClicked: root.filterSelected(modelData.key)
            }
        }
    }
}
