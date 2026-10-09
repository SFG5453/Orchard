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
import QtQuick.Controls
import ".."

// Column labels. Geometry mirrors CollectionTrackRow so the headings line up.
Item {
    id: root

    property bool showArtwork: true
    property bool showAlbum: false
    property color labelColor: "#99ffffff"
    property color activeColor: "white"
    // Sorting is opt-in; albums already come in the order the artist meant.
    property bool sortable: false
    property string sortKey
    property bool sortDescending: false

    readonly property real albumWidth: showAlbum ? Math.round(width * 0.26) : 0

    signal sortRequested(string key, bool descending)

    // Header clicks walk ascending, descending, then back to playlist order.
    function cycleSort(key) {
        if (key === "" || root.sortKey !== key)
            root.sortRequested(key, false);
        else if (!root.sortDescending)
            root.sortRequested(key, true);
        else
            root.sortRequested("", false);
    }

    // Menu picks flip direction on the active key instead of clearing it.
    function chooseSort(key) {
        root.sortRequested(key, key !== "" && key === root.sortKey && !root.sortDescending);
    }

    function sortName(key) {
        return ({"": qsTr("Custom order"), title: qsTr("Title"), artist: qsTr("Artist"),
                 album: qsTr("Album"), duration: qsTr("Duration")})[key] || "";
    }

    height: 40

    component SortLabel: Item {
        id: label

        property string key
        property string text
        property string icon
        property bool alignRight: false
        readonly property bool active: key !== "" && root.sortKey === key
        readonly property color tone: active || labelMouse.containsMouse ? root.activeColor : root.labelColor

        implicitWidth: row.implicitWidth
        height: root.height
        Accessible.role: Accessible.Button
        Accessible.name: qsTr("Sort by %1").arg(root.sortName(key))

        Row {
            id: row
            x: Math.round((label.width - width) / 2)
            anchors.verticalCenter: parent.verticalCenter
            layoutDirection: label.alignRight ? Qt.RightToLeft : Qt.LeftToRight
            spacing: 4
            Text {
                visible: label.text !== ""
                text: label.text
                color: label.tone
                font.family: "Inter"
                font.pixelSize: 10
                font.weight: Font.DemiBold
                font.letterSpacing: 1.2
            }
            LucideIcon {
                visible: label.icon !== ""
                width: 14
                height: 14
                name: label.icon
                color: label.tone
            }
            LucideIcon {
                visible: label.active
                width: 12
                height: 12
                anchors.verticalCenter: parent.verticalCenter
                name: root.sortDescending ? "chevron-down" : "chevron-up"
                color: root.activeColor
            }
        }
        MouseArea {
            id: labelMouse
            anchors.fill: parent
            enabled: root.sortable
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: root.cycleSort(label.key)
        }
    }

    component SortEntry: MenuItem {
        id: entry

        property string key
        readonly property bool active: root.sortKey === key

        text: root.sortName(key)
        implicitHeight: 36
        onTriggered: root.chooseSort(key)
        background: Rectangle { radius: 6; color: entry.highlighted ? "#435247" : "transparent" }
        contentItem: Item {
            Text {
                anchors.left: parent.left
                anchors.right: marker.left
                anchors.verticalCenter: parent.verticalCenter
                text: entry.text
                color: entry.active ? root.activeColor : "#f2f0eb"
                font.family: "Inter"
                font.pixelSize: 13
                font.weight: entry.active ? Font.DemiBold : Font.Normal
                elide: Text.ElideRight
            }
            LucideIcon {
                id: marker
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: entry.active && entry.key !== ""
                width: 14
                height: 14
                name: root.sortDescending ? "chevron-down" : "chevron-up"
                color: root.activeColor
            }
        }
    }

    SortLabel {
        x: 12
        width: 32
        key: ""
        text: "#"
        // "#" means playlist order, so it never shows a direction.
        Accessible.name: qsTr("Restore custom order")
    }
    SortLabel {
        x: 12 + 32 + 14 + (root.showArtwork ? 44 + 14 : 0)
        key: "title"
        text: qsTr("TITLE")
    }
    SortLabel {
        x: root.width - 8 - 36 - 48 - 16 - root.albumWidth
        visible: root.showAlbum
        key: "album"
        text: qsTr("ALBUM")
    }
    SortLabel {
        x: root.width - 8 - 36 - width
        key: "duration"
        icon: "clock"
        alignRight: true
    }

    ToolButton {
        id: sortButton
        x: root.width - 8 - width
        width: 36
        height: 36
        anchors.verticalCenter: parent.verticalCenter
        visible: root.sortable
        padding: 11
        Accessible.name: qsTr("Sort tracks")
        onClicked: sortMenu.popup(sortButton, sortButton.width - sortMenu.width, sortButton.height)
        background: Rectangle {
            radius: width / 2
            color: sortButton.hovered || sortMenu.visible ? "#1fffffff" : "transparent"
        }
        contentItem: LucideIcon {
            name: "arrow-up-down"
            color: root.sortKey !== "" || sortButton.hovered ? root.activeColor : root.labelColor
        }
    }

    Menu {
        id: sortMenu
        width: 190
        padding: 5
        // Keep in the scene like MediaMenu so it matches on every desktop.
        Component.onCompleted: if ("popupType" in sortMenu) sortMenu["popupType"] = 0
        background: Rectangle { color: "#242c27"; radius: 10; border.color: "#455348" }
        SortEntry { key: "" }
        SortEntry { key: "title" }
        SortEntry { key: "artist" }
        SortEntry { key: "album" }
        SortEntry { key: "duration" }
    }

    Rectangle {
        anchors.bottom: parent.bottom
        x: 12
        width: parent.width - 24
        height: 1
        color: "#12ffffff"
    }
}
