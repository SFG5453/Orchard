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
import QtQuick.Layouts

// Shortcuts for the keyboard-warriors who consider touching the mouse a personal defeat.
ColumnLayout {
    id: root

    spacing: 28
    Layout.fillWidth: true

    // Entries with separator set are plain text ("or", "+", "/"); the rest are key caps.
    readonly property var groups: [
        {
            title: qsTr("Search & Discovery"),
            items: [
                { name: qsTr("Spotlight Search"), icon: "search",
                  desc: qsTr("Universal search for songs, albums, artists, and navigation across Orchard."),
                  keys: [{ label: "/" }, { label: qsTr("or"), separator: true }, { label: "Ctrl" }, { label: "+", separator: true }, { label: "K" }] },
                { name: qsTr("Playlist Quick Search"), icon: "list-music",
                  desc: qsTr("Type any character in a loaded playlist to instantly find and center matching tracks."),
                  keys: [{ label: "A-Z" }, { label: "/", separator: true }, { label: "0-9" }] },
                { name: qsTr("Navigate Search Matches"), icon: "arrow-up-down",
                  desc: qsTr("Move selection through matches and live-preview their position."),
                  keys: [{ label: "↑" }, { label: "↓" }] },
                { name: qsTr("Execute / Play Selection"), icon: "play",
                  desc: qsTr("Open the focused search item or play the selected playlist track."),
                  keys: [{ label: "↵ Enter" }] }
            ]
        },
        {
            title: qsTr("Navigation & Windows"),
            items: [
                { name: qsTr("Dismiss / Go Back"), icon: "undo-2",
                  desc: qsTr("Close open search modals, dismiss popups, or navigate back to the previous view."),
                  keys: [{ label: "Esc" }] },
                { name: qsTr("Toggle Maximize"), icon: "app-window",
                  desc: qsTr("Double click the title bar to expand or restore the window."),
                  keys: [{ label: qsTr("Double Click Title Bar") }] }
            ]
        },
        {
            title: qsTr("Playback & Media Controls"),
            items: [
                { name: qsTr("Play / Pause"), icon: "pause",
                  desc: qsTr("Toggle playback of the active track."),
                  keys: [{ label: "Space" }, { label: qsTr("or"), separator: true }, { label: "Media Play" }] },
                { name: qsTr("Seek Backward / Forward"), icon: "chevron-right",
                  desc: qsTr("Jump 5 seconds backward or forward in the active track."),
                  keys: [{ label: "←" }, { label: "→" }] },
                { name: qsTr("Previous / Next Track"), icon: "skip-forward",
                  desc: qsTr("Skip backward or forward through the queue using your keyboard media keys."),
                  keys: [{ label: "Media Prev" }, { label: "Media Next" }] },
                { name: qsTr("Now Playing"), icon: "music-2",
                  desc: qsTr("Open or close the fullscreen player."),
                  keys: [{ label: "F" }, { label: "Esc" }] }
            ]
        }
    ]

    Repeater {
        model: root.groups

        delegate: SettingsSection {
            id: group
            required property var modelData

            Layout.fillWidth: true
            title: modelData.title
            rowSpacing: 0
            verticalPadding: 0

            Repeater {
                model: group.modelData.items

                delegate: SettingsRow {
                    id: shortcut
                    required property var modelData

                    iconName: modelData.icon
                    title: modelData.name
                    description: modelData.desc

                    Row {
                        spacing: 6
                        Layout.alignment: Qt.AlignVCenter

                        Repeater {
                            model: shortcut.modelData.keys
                            delegate: ShortcutKey {
                                required property var modelData
                                label: modelData.label
                                separator: !!modelData.separator
                            }
                        }
                    }
                }
            }
        }
    }
}
