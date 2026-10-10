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
    property var tracks: []
    property var detail: ({})
    property string playlistTitle: detail.title || ""

    property var matches: []
    property int activeIndex: 0
    property int focusedTrackIndex: -1

    signal matchFocused(int trackIndex)
    signal trackSelected(var track, int trackIndex)

    parent: Overlay.overlay
    x: parent ? Math.round((parent.width - width) / 2) : 0
    y: 70
    width: Math.min(520, parent.width - 32)
    height: Math.min(460, contentCol.implicitHeight + 2)
    padding: 0
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    Overlay.modal: Rectangle {
        color: "#75000000"
    }

    enter: Transition {
        ParallelAnimation {
            NumberAnimation {
                property: "opacity"
                from: 0.0
                to: 1.0
                duration: 200
                easing.type: Easing.OutCubic
            }
            NumberAnimation {
                property: "scale"
                from: 0.96
                to: 1.0
                duration: 200
                easing.type: Easing.OutCubic
            }
        }
    }

    exit: Transition {
        ParallelAnimation {
            NumberAnimation {
                property: "opacity"
                from: 1.0
                to: 0.0
                duration: 160
                easing.type: Easing.InCubic
            }
            NumberAnimation {
                property: "scale"
                from: 1.0
                to: 0.96
                duration: 160
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

    function normalizeText(value) {
        return String(value || "")
            .normalize("NFKD")
            .replace(/[\u0300-\u036f]/g, "")
            .toLowerCase()
            .trim();
    }

    function wordStartsWith(text, q) {
        const words = text.split(/\s+/);
        for (let i = 0; i < words.length; ++i) {
            if (words[i].startsWith(q))
                return true;
        }
        return false;
    }

    function matchScore(text, q, baseScore) {
        if (!text || !q)
            return null;
        if (text === q)
            return baseScore;
        if (text.startsWith(q))
            return baseScore + 1;
        if (wordStartsWith(text, q))
            return baseScore + 2;
        if (text.indexOf(q) !== -1)
            return baseScore + 3;
        return null;
    }

    function trackArtist(track) {
        if (track.artists && track.artists.length) {
            const joined = track.artists.map(a => (a && a.name) ? a.name : a).filter(Boolean).join(", ");
            if (joined)
                return joined;
        }
        if (track.artist)
            return track.artist;
        return root.detail.author || root.detail.subtitle || "";
    }

    function computeMatches(q) {
        const query = normalizeText(q);
        if (!query || !root.tracks || !root.tracks.length)
            return [];

        const results = [];
        for (let i = 0; i < root.tracks.length; ++i) {
            const track = root.tracks[i];
            const title = normalizeText(track.title);
            const artist = normalizeText(root.trackArtist(track));
            const album = normalizeText(track.album);

            const scores = [];
            const s1 = matchScore(title, query, 0);
            if (s1 !== null) scores.push(s1);
            const s2 = matchScore(artist, query, 4);
            if (s2 !== null) scores.push(s2);
            const s3 = matchScore(album, query, 8);
            if (s3 !== null) scores.push(s3);

            if (scores.length > 0) {
                const bestScore = Math.min(...scores);
                const subtitleParts = [];
                const artistDisplay = root.trackArtist(track);
                if (artistDisplay)
                    subtitleParts.push(artistDisplay);
                if (track.album)
                    subtitleParts.push(track.album);

                results.push({
                    id: `${track.id || i}:${i}`,
                    index: i,
                    score: bestScore,
                    track: track,
                    title: track.title || qsTr("Untitled track"),
                    subtitle: subtitleParts.join(" • "),
                    artwork: track.thumbnail || root.detail.thumbnail || ""
                });
            }
        }

        results.sort((a, b) => a.score - b.score || a.index - b.index);
        return results.slice(0, 10);
    }

    function updateMatches() {
        const q = searchInput.text.trim();
        root.matches = computeMatches(q);
        if (root.activeIndex >= root.matches.length) {
            root.activeIndex = Math.max(0, root.matches.length - 1);
        }
        notifyMatchFocus();
    }

    function notifyMatchFocus() {
        if (root.matches.length > 0 && root.activeIndex >= 0 && root.activeIndex < root.matches.length) {
            const targetIndex = root.matches[root.activeIndex].index;
            root.focusedTrackIndex = targetIndex;
            root.matchFocused(targetIndex);
        } else {
            root.focusedTrackIndex = -1;
        }
    }

    function moveActiveIndex(offset) {
        if (!root.matches.length)
            return;
        root.activeIndex = (root.activeIndex + offset + root.matches.length) % root.matches.length;
        resultsList.positionViewAtIndex(root.activeIndex, ListView.Contain);
        notifyMatchFocus();
    }

    function selectActiveMatch() {
        if (root.matches.length > 0 && root.activeIndex >= 0 && root.activeIndex < root.matches.length) {
            const match = root.matches[root.activeIndex];
            root.close();
            root.trackSelected(match.track, match.index);
        }
    }

    function openWithQuery(initialQuery) {
        searchInput.text = initialQuery || "";
        root.activeIndex = 0;
        root.open();
        root.updateMatches();
        searchInput.forceActiveFocus();
        searchInput.cursorPosition = searchInput.text.length;
    }

    onOpened: {
        searchInput.forceActiveFocus();
        searchInput.cursorPosition = searchInput.text.length;
    }

    onClosed: {
        root.focusedTrackIndex = -1;
    }

    contentItem: ColumnLayout {
        id: contentCol
        spacing: 0
        clip: true

        // Top Search Input Row
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 50
            Layout.leftMargin: 14
            Layout.rightMargin: 12
            spacing: 10

            LucideIcon {
                Layout.preferredWidth: 18
                Layout.preferredHeight: 18
                name: "search"
                color: "#9ca7a0"
            }

            TextField {
                id: searchInput
                Layout.fillWidth: true
                placeholderText: root.playlistTitle ? qsTr("Find in %1…").arg(root.playlistTitle) : qsTr("Find in playlist…")
                placeholderTextColor: "#6a7770"
                color: "#f2f5f2"
                font.family: "Inter"
                font.pixelSize: 14
                background: Item {}
                selectByMouse: true

                onTextChanged: {
                    root.updateMatches();
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
                    root.selectActiveMatch();
                }
                Keys.onEnterPressed: function(event) {
                    event.accepted = true;
                    root.selectActiveMatch();
                }
                Keys.onEscapePressed: function(event) {
                    event.accepted = true;
                    root.close();
                }
            }

            Text {
                text: {
                    if (!searchInput.text.trim())
                        return qsTr("Type to find");
                    const count = root.matches.length;
                    return count === 1 ? qsTr("1 match") : qsTr("%1 matches").arg(count);
                }
                color: "#748078"
                font.family: "Inter"
                font.pixelSize: 11
            }

            Button {
                id: clearBtn
                Layout.preferredWidth: 26
                Layout.preferredHeight: 26
                visible: searchInput.text.length > 0
                Accessible.name: qsTr("Clear search")
                onClicked: {
                    searchInput.clear();
                    searchInput.forceActiveFocus();
                }
                background: Rectangle {
                    radius: 13
                    color: clearBtn.hovered ? "#22ffffff" : "transparent"
                }
                contentItem: Text {
                    text: "×"
                    color: "#a4aca6"
                    font.pixelSize: 18
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
            }

            Rectangle {
                Layout.preferredHeight: 20
                Layout.preferredWidth: escText.implicitWidth + 10
                radius: 4
                color: "#18ffffff"
                border.color: "#20ffffff"

                Text {
                    id: escText
                    anchors.centerIn: parent
                    text: "ESC"
                    color: "#828e86"
                    font.family: "Inter"
                    font.pixelSize: 9
                    font.weight: Font.DemiBold
                }

                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: root.close()
                }
            }
        }

        // Subtly separates search bar from results
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: "#14ffffff"
        }

        // Results List
        ListView {
            id: resultsList
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(320, contentHeight)
            Layout.margins: 6
            clip: true
            spacing: 2
            visible: root.matches.length > 0
            model: root.matches

            ScrollBar.vertical: ScrollBar {
                width: 4
                policy: ScrollBar.AsNeeded
            }

            delegate: Rectangle {
                id: rowItem
                required property var modelData
                required property int index

                width: resultsList.width
                height: 48
                radius: 7
                color: index === root.activeIndex ? "#1effffff" : (rowMouse.containsMouse ? "#0dffffff" : "transparent")
                border.color: index === root.activeIndex ? "#30ffffff" : "transparent"
                border.width: 1

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 8
                    anchors.rightMargin: 10
                    spacing: 10

                    Item {
                        Layout.preferredWidth: 32
                        Layout.preferredHeight: 32

                        RoundedArtwork {
                            anchors.fill: parent
                            radius: 5
                            source: rowItem.modelData.artwork || ""
                            visible: Boolean(rowItem.modelData.artwork)
                        }

                        Rectangle {
                            anchors.fill: parent
                            radius: 5
                            color: "#1a221d"
                            visible: !rowItem.modelData.artwork

                            LucideIcon {
                                anchors.centerIn: parent
                                width: 16
                                height: 16
                                name: "music-2"
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

                    // Track number in playlist
                    Text {
                        text: String(rowItem.modelData.index + 1)
                        color: rowItem.index === root.activeIndex ? "#adb8af" : "#69746c"
                        font.family: "Inter"
                        font.pixelSize: 11
                        font.weight: Font.Medium
                    }
                }

                MouseArea {
                    id: rowMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onEntered: {
                        root.activeIndex = rowItem.index;
                        root.notifyMatchFocus();
                    }
                    onClicked: {
                        root.close();
                        root.trackSelected(rowItem.modelData.track, rowItem.modelData.index);
                    }
                }
            }
        }

        // Empty Status Placeholder
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 80
            visible: searchInput.text.trim().length > 0 && root.matches.length === 0

            ColumnLayout {
                anchors.centerIn: parent
                spacing: 6

                LucideIcon {
                    Layout.alignment: Qt.AlignHCenter
                    width: 24
                    height: 24
                    name: "search"
                    color: "#525e56"
                }

                Text {
                    Layout.alignment: Qt.AlignHCenter
                    text: qsTr("No tracks match “%1”").arg(searchInput.text.trim())
                    color: "#7e8a81"
                    font.family: "Inter"
                    font.pixelSize: 12
                }
            }
        }

        // Footer Hint Bar
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: "#12ffffff"
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 26
            Layout.leftMargin: 12
            Layout.rightMargin: 12

            Text {
                text: qsTr("↑↓ Navigate   ↵ Play   Esc Close")
                color: "#556059"
                font.family: "Inter"
                font.pixelSize: 10
            }

            Item { Layout.fillWidth: true }

            Text {
                text: qsTr("Quick Search")
                color: "#4a544d"
                font.family: "Inter"
                font.pixelSize: 10
                font.weight: Font.DemiBold
            }
        }
    }
}
