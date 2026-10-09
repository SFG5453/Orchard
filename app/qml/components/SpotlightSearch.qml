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
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Orchard
import "."
import "home"

Popup {
    id: root

    required property Item backdrop

    property var spotlightRows: []
    property int activeIndex: 0

    signal pageRequested(string page)
    signal searchRequested(string query)
    signal detailRequested(string page, var item)
    signal songRequested(var song)
    signal unsupportedRequested(var media)
    signal settingsRequested
    signal docsRequested
    signal supportRequested
    signal queueRequested

    parent: Overlay.overlay
    x: parent ? Math.round((parent.width - width) / 2) : 0
    y: 64
    width: Math.min(620, parent.width - 32)
    height: Math.min(520, parent.height - 96)
    padding: 0
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    Overlay.modal: Rectangle {
        color: "#75000000"
    }

    // Silky smooth entrance: fade + gentle scale up
    enter: Transition {
        ParallelAnimation {
            NumberAnimation {
                property: "opacity"
                from: 0.0
                to: 1.0
                duration: 220
                easing.type: Easing.OutCubic
            }
            NumberAnimation {
                property: "scale"
                from: 0.96
                to: 1.0
                duration: 220
                easing.type: Easing.OutCubic
            }
        }
    }

    // Graceful exit: fade + slight shrink
    exit: Transition {
        ParallelAnimation {
            NumberAnimation {
                property: "opacity"
                from: 1.0
                to: 0.0
                duration: 180
                easing.type: Easing.InCubic
            }
            NumberAnimation {
                property: "scale"
                from: 1.0
                to: 0.96
                duration: 180
                easing.type: Easing.InCubic
            }
        }
    }

    background: Loader {
        active: root.visible
        sourceComponent: Item {
            Glass {
                anchors.fill: parent
                backdrop: root.backdrop
                radius: 14
            }

            Rectangle {
                anchors.fill: parent
                radius: 14
                color: "#180e1210"
                border.color: "#28ffffff"
                border.width: 1
            }
        }
    }

    function openSpotlight(initialQuery) {
        searchInput.text = initialQuery || "";
        root.activeIndex = 0;
        root.open();
        root.updateRows();
    }

    onAboutToShow: {
        root.activeIndex = 0;
        root.updateRows();
    }

    onOpened: {
        searchInput.forceActiveFocus();
        searchInput.selectAll();
    }

    function moveActiveIndex(offset) {
        if (!spotlightRows.length)
            return;
        root.activeIndex = (root.activeIndex + offset + spotlightRows.length) % spotlightRows.length;
        resultsList.positionViewAtIndex(root.activeIndex, ListView.Contain);
    }

    function activateRow(row) {
        if (!row || !row.action)
            return;
        root.close();
        row.action();
    }

    function activateActiveRow() {
        if (spotlightRows.length > 0 && root.activeIndex >= 0 && root.activeIndex < spotlightRows.length) {
            activateRow(spotlightRows[root.activeIndex]);
        } else if (searchInput.text.trim().length > 0) {
            const q = searchInput.text.trim();
            root.close();
            root.searchRequested(q);
        }
    }

    function activateMedia(item, category) {
        if (category === "videos" || item.type === "video") {
            root.unsupportedRequested(item);
        } else if (item.type === "album" || category === "albums") {
            root.detailRequested("album", item);
        } else if (item.type === "artist" || category === "artists") {
            root.detailRequested("artist", item);
        } else if (item.type === "playlist" || category === "playlists") {
            root.detailRequested("playlist", item);
        } else {
            root.songRequested(item);
        }
    }

    function buildRows() {
        const queryText = searchInput.text.trim();
        const q = queryText.toLowerCase();
        const rows = [];

        // Only use things we have now in v3:
        const allCommands = [
            {
                id: "cmd:home",
                title: qsTr("Home"),
                subtitle: qsTr("Library and recommendations"),
                icon: "home",
                type: qsTr("Page"),
                action: () => { root.pageRequested("home"); }
            },
            {
                id: "cmd:library",
                title: qsTr("Your library"),
                subtitle: qsTr("Saved playlists and songs"),
                icon: "library-big",
                type: qsTr("Page"),
                action: () => { root.pageRequested("library"); }
            },
            {
                id: "cmd:queue",
                title: qsTr("Queue"),
                subtitle: qsTr("Upcoming playback queue"),
                icon: "music-2",
                type: qsTr("Action"),
                action: () => { root.queueRequested(); }
            },
            {
                id: "cmd:settings",
                title: qsTr("Settings"),
                subtitle: qsTr("Playback, appearance, and integrations"),
                icon: "compass",
                type: qsTr("Settings"),
                action: () => { root.settingsRequested(); }
            },
            {
                id: "cmd:docs",
                title: qsTr("Docs"),
                subtitle: qsTr("Guides for every Orchard feature"),
                icon: "book-open",
                type: qsTr("Help"),
                action: () => { root.docsRequested(); }
            },
            {
                id: "cmd:support",
                title: qsTr("Report a bug"),
                subtitle: qsTr("Send a bug, idea, or feedback with a screenshot"),
                icon: "bug",
                type: qsTr("Help"),
                action: () => { root.supportRequested(); }
            }
        ];

        if (!queryText) {
            rows.push({
                id: "cmd:search",
                title: qsTr("Search Orchard"),
                subtitle: qsTr("Songs, albums, artists, and playlists"),
                icon: "search",
                type: qsTr("Page"),
                action: () => { root.pageRequested("search"); }
            });
            for (let i = 0; i < allCommands.length; ++i) {
                rows.push(allCommands[i]);
            }
            return rows;
        }

        // Search query row is king of the hill when typing
        rows.push({
            id: "cmd:full-search",
            title: qsTr("Search for “%1”").arg(queryText),
            subtitle: qsTr("Open full search results"),
            icon: "search",
            type: qsTr("Search"),
            action: () => { root.searchRequested(queryText); }
        });

        // Filter matching navigation commands
        for (let i = 0; i < allCommands.length; ++i) {
            const cmd = allCommands[i];
            const searchable = (cmd.title + " " + cmd.subtitle).toLowerCase();
            if (searchable.includes(q)) {
                rows.push(cmd);
            }
        }

        // Map live catalog sections into spotlight results
        const sections = OrchardSearch.sections || [];
        for (let s = 0; s < sections.length; ++s) {
            const section = sections[s];
            const items = section.items || [];
            for (let i = 0; i < items.length; ++i) {
                const item = items[i];
                const sectionTitle = section.title || "";
                const credit = item.artist || (item.artists || []).join(", ") || item.subtitle || "";
                const subtitleParts = [];
                if (credit)
                    subtitleParts.push(credit);
                if (sectionTitle && sectionTitle !== "Top result" && sectionTitle !== "Songs") {
                    subtitleParts.push(sectionTitle);
                }

                let typeLabel = "";
                let fallbackIcon = "music-2";
                if (item.type === "song" || section.key === "songs") {
                    typeLabel = qsTr("Song");
                    fallbackIcon = "music-2";
                } else if (item.type === "album" || section.key === "albums") {
                    typeLabel = qsTr("Album");
                    fallbackIcon = "library-big";
                } else if (item.type === "artist" || section.key === "artists") {
                    typeLabel = qsTr("Artist");
                    fallbackIcon = "circle-user-round";
                } else if (item.type === "playlist" || section.key === "playlists") {
                    typeLabel = qsTr("Playlist");
                    fallbackIcon = "library-big";
                } else if (item.type === "video" || section.key === "videos") {
                    typeLabel = qsTr("Video");
                    fallbackIcon = "play";
                } else {
                    typeLabel = item.type || "";
                }

                rows.push({
                    id: "media:" + section.key + ":" + (item.id || i),
                    title: item.title || qsTr("Untitled"),
                    subtitle: subtitleParts.join(" • "),
                    type: typeLabel,
                    icon: fallbackIcon,
                    artwork: item.thumbnail || "",
                    item: item,
                    category: section.key,
                    action: () => {
                        root.activateMedia(item, section.key);
                    }
                });

                if (rows.length >= 10)
                    break;
            }
            if (rows.length >= 10)
                break;
        }

        return rows;
    }

    function updateRows() {
        root.spotlightRows = buildRows();
        if (root.activeIndex >= root.spotlightRows.length) {
            root.activeIndex = Math.max(0, root.spotlightRows.length - 1);
        }
    }

    Timer {
        id: debounceTimer
        interval: 180
        repeat: false
        onTriggered: {
            const q = searchInput.text.trim();
            if (q.length > 0 && OrchardAuth.isSignedIn) {
                OrchardSearch.search(q, "all");
            }
        }
    }

    Connections {
        target: OrchardSearch
        function onSectionsChanged() { root.updateRows(); }
        function onStateChanged() { root.updateRows(); }
    }

    contentItem: ColumnLayout {
        spacing: 0
        clip: true

        // Top Search Bar
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 52
            Layout.leftMargin: 16
            Layout.rightMargin: 12
            spacing: 12

            LucideIcon {
                Layout.preferredWidth: 20
                Layout.preferredHeight: 20
                name: "search"
                color: "#9ca7a0"
            }

            TextField {
                id: searchInput
                Layout.fillWidth: true
                placeholderText: qsTr("Search songs, albums, artists, pages…")
                placeholderTextColor: "#6a7770"
                color: "#f2f5f2"
                font.family: "Inter"
                font.pixelSize: 15
                background: Item {}
                selectByMouse: true

                onTextChanged: {
                    root.updateRows();
                    debounceTimer.restart();
                }

                Keys.onDownPressed: function(event) {
                    event.accepted = true;
                    root.moveActiveIndex(1);
                }
                Keys.onUpPressed: function(event) {
                    event.accepted = true;
                    root.moveActiveIndex(-1);
                }
                Keys.onReturnPressed: function(event) {
                    event.accepted = true;
                    root.activateActiveRow();
                }
                Keys.onEnterPressed: function(event) {
                    event.accepted = true;
                    root.activateActiveRow();
                }
                Keys.onEscapePressed: function(event) {
                    event.accepted = true;
                    root.close();
                }
            }

            Button {
                id: clearBtn
                Layout.preferredWidth: 28
                Layout.preferredHeight: 28
                visible: searchInput.text.length > 0
                Accessible.name: qsTr("Clear search")
                onClicked: {
                    searchInput.clear();
                    searchInput.forceActiveFocus();
                }
                background: Rectangle {
                    radius: 14
                    color: clearBtn.hovered ? "#22ffffff" : "transparent"
                }
                contentItem: Text {
                    text: "×"
                    color: "#a4aca6"
                    font.pixelSize: 20
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
            }

            Rectangle {
                Layout.preferredHeight: 22
                Layout.preferredWidth: escLabel.implicitWidth + 12
                radius: 5
                color: "#18ffffff"
                border.color: "#20ffffff"

                Text {
                    id: escLabel
                    anchors.centerIn: parent
                    text: "ESC"
                    color: "#828e86"
                    font.family: "Inter"
                    font.pixelSize: 10
                    font.weight: Font.DemiBold
                }

                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: root.close()
                }
            }
        }

        // Subtly separates search input from results
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: "#14ffffff"
        }

        // Loading accent line when searching
        Item {
            id: progressBar
            Layout.fillWidth: true
            Layout.preferredHeight: 2
            visible: OrchardSearch.loading
            clip: true

            Rectangle {
                id: progressThumb
                width: parent.width * 0.35
                height: 2
                color: "#68d391"

                NumberAnimation on x {
                    running: progressBar.visible
                    loops: Animation.Infinite
                    from: -progressThumb.width
                    to: progressBar.width
                    duration: 960
                    easing.type: Easing.InOutQuad
                }
            }
        }

        // Search Results List
        ListView {
            id: resultsList
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.margins: 6
            clip: true
            spacing: 2
            visible: root.spotlightRows.length > 0
            model: root.spotlightRows

            ScrollBar.vertical: ScrollBar {
                width: 4
                policy: ScrollBar.AsNeeded
            }

            delegate: Rectangle {
                id: rowItem
                required property var modelData
                required property int index

                width: resultsList.width
                height: 50
                radius: 8
                color: index === root.activeIndex ? "#1effffff" : (rowMouse.containsMouse ? "#0dffffff" : "transparent")
                border.color: index === root.activeIndex ? "#30ffffff" : "transparent"
                border.width: 1

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 8
                    anchors.rightMargin: 12
                    spacing: 12

                    Item {
                        Layout.preferredWidth: 34
                        Layout.preferredHeight: 34

                        RoundedArtwork {
                            anchors.fill: parent
                            radius: rowItem.modelData.category === "artists" ? 17 : 5
                            source: rowItem.modelData.artwork || ""
                            visible: Boolean(rowItem.modelData.artwork)
                        }

                        Rectangle {
                            anchors.fill: parent
                            radius: 6
                            color: "#1a221d"
                            visible: !rowItem.modelData.artwork

                            LucideIcon {
                                anchors.centerIn: parent
                                width: 17
                                height: 17
                                name: rowItem.modelData.icon || "search"
                                color: rowItem.index === root.activeIndex ? "#e3e9e4" : "#9ca7a0"
                            }
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2

                        Text {
                            Layout.fillWidth: true
                            text: rowItem.modelData.title || ""
                            color: rowItem.index === root.activeIndex ? "#ffffff" : "#f0f3f0"
                            font.family: "Inter"
                            font.pixelSize: 13
                            font.weight: Font.DemiBold
                            elide: Text.ElideRight
                        }

                        Text {
                            Layout.fillWidth: true
                            text: rowItem.modelData.subtitle || ""
                            color: rowItem.index === root.activeIndex ? "#b0bcb2" : "#838f85"
                            font.family: "Inter"
                            font.pixelSize: 11
                            elide: Text.ElideRight
                            visible: text.length > 0
                        }
                    }

                    Rectangle {
                        Layout.preferredHeight: 20
                        Layout.preferredWidth: typeLabel.implicitWidth + 12
                        visible: Boolean(rowItem.modelData.type)
                        radius: 4
                        color: rowItem.index === root.activeIndex ? "#25ffffff" : "#10ffffff"

                        Text {
                            id: typeLabel
                            anchors.centerIn: parent
                            text: rowItem.modelData.type || ""
                            color: rowItem.index === root.activeIndex ? "#c5cec7" : "#7d8980"
                            font.family: "Inter"
                            font.pixelSize: 10
                            font.weight: Font.Medium
                        }
                    }
                }

                MouseArea {
                    id: rowMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onEntered: root.activeIndex = rowItem.index
                    onClicked: root.activateRow(rowItem.modelData)
                }
            }
        }

        // Empty Status Placeholder
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: root.spotlightRows.length === 0

            ColumnLayout {
                anchors.centerIn: parent
                spacing: 10

                BusyIndicator {
                    Layout.alignment: Qt.AlignHCenter
                    running: OrchardSearch.loading
                    visible: running
                }

                LucideIcon {
                    Layout.alignment: Qt.AlignHCenter
                    width: 32
                    height: 32
                    name: "search"
                    color: "#525e56"
                    visible: !OrchardSearch.loading
                }

                Text {
                    Layout.alignment: Qt.AlignHCenter
                    text: OrchardSearch.loading ? qsTr("Searching…") : qsTr("No matches found")
                    color: "#7e8a81"
                    font.family: "Inter"
                    font.pixelSize: 13
                }
            }
        }

        // Bottom Footer Bar with Shortcuts Hint
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: "#12ffffff"
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 28
            Layout.leftMargin: 14
            Layout.rightMargin: 14

            Text {
                text: qsTr("↑↓ Navigate   ↵ Select   Esc Close")
                color: "#5b665f"
                font.family: "Inter"
                font.pixelSize: 10
            }

            Item { Layout.fillWidth: true }

            Text {
                text: qsTr("Spotlight")
                color: "#4e5952"
                font.family: "Inter"
                font.pixelSize: 10
                font.weight: Font.DemiBold
            }
        }
    }
}
