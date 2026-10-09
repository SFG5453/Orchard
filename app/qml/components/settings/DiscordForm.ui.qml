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
import QtQuick.Layouts

// Tell your friends what you're listening to. They didn't ask, but now they know.
ColumnLayout {
    id: root

    property var discord: ({
        enabled: true,
        showSyncedLyrics: true,
        activityText: "{song}",
        detailsText: "{artist}",
        stateText: "",
        platform: "YouTube Music",
        activityType: "listening",
        statusDisplay: "name",
        projectButtonEnabled: false,
        animatedArtworkEnabled: false,
        connected: true,
        lastError: ""
    })
    property bool accountSignedIn: false
    readonly property var platformValues: ["YouTube Music", "Orchard V3"]
    readonly property var activityValues: ["listening", "playing", "watching", "competing"]
    readonly property var statusDisplayValues: ["name", "details", "state"]
    property alias enableSwitch: enableSwitch
    property alias lyricsSwitch: lyricsSwitch
    property alias activityField: activityField
    property alias detailsField: detailsField
    property alias stateField: stateField
    property alias platformSelect: platformSelect
    property alias activitySelect: activitySelect
    property alias statusSelect: statusSelect
    property alias projectSwitch: projectSwitch
    property alias animatedSwitch: animatedSwitch

    spacing: 22

    RowLayout {
        Layout.fillWidth: true
        spacing: 20

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4

            Text {
                text: qsTr("Discord Rich Presence")
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 14
                font.weight: Font.Medium
            }

            SettingsHint {
                text: qsTr("Show your current track, artwork, and a link in Discord.")
            }
        }

        SettingsSwitch {
            id: enableSwitch
            Layout.alignment: Qt.AlignVCenter
            padding: 0
            checked: root.discord.enabled
            Accessible.name: qsTr("Enable Discord Rich Presence")
        }
    }

    Rectangle {
        Layout.fillWidth: true
        Layout.preferredHeight: 1
        color: "#12ffffff"
    }

    ColumnLayout {
        Layout.fillWidth: true
        spacing: 22
        enabled: root.discord.enabled
        opacity: enabled ? 1.0 : 0.46

        SettingsHint {
            text: qsTr("The selected platform is shown in Discord as ‘Listening to …’. The activity text below defaults to the song name, but you can customize it.")
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 20

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4

                SettingsFieldLabel {
                    text: qsTr("Show Synced Lyrics")
                }

                SettingsHint {
                    text: qsTr("Show the current lyric line as Discord state when synced lyrics are available.")
                }
            }

            SettingsSwitch {
                id: lyricsSwitch
                Layout.alignment: Qt.AlignVCenter
                padding: 0
                checked: root.discord.showSyncedLyrics
                Accessible.name: qsTr("Show Synced Lyrics")
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 8

            SettingsFieldLabel {
                text: qsTr("Activity text template")
            }

            SettingsTextField {
                id: activityField
                text: root.discord.activityText
                placeholderText: qsTr("{song}")
                Accessible.name: qsTr("Discord activity text template")
            }

            SettingsHint {
                text: qsTr("Variables: {song}, {artist}, {album}, {platform}, {status}, {app}, {position}, {duration}, {url}")
                font.pixelSize: 11
            }
        }

        GridLayout {
            Layout.fillWidth: true
            columns: 2
            columnSpacing: 16
            rowSpacing: 20
            // Equal preferred widths keep both columns the same size regardless of content.
            uniformCellWidths: true

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8

                SettingsFieldLabel {
                    text: qsTr("Secondary text template")
                }

                SettingsTextField {
                    id: detailsField
                    text: root.discord.detailsText
                    placeholderText: qsTr("Optional")
                    Accessible.name: qsTr("Discord secondary text template")
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8

                SettingsFieldLabel {
                    text: qsTr("State template")
                }

                SettingsTextField {
                    id: stateField
                    text: root.discord.stateText
                    placeholderText: qsTr("Optional")
                    Accessible.name: qsTr("Discord state template")
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8

                SettingsFieldLabel {
                    text: qsTr("Platform")
                }

                SettingsSelect {
                    id: platformSelect
                    model: [qsTr("YouTube Music"), qsTr("Orchard V3")]
                    Accessible.name: qsTr("Platform shown in Discord")
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8

                SettingsFieldLabel {
                    text: qsTr("Activity type")
                }

                SettingsSelect {
                    id: activitySelect
                    model: [qsTr("Listening"), qsTr("Playing"), qsTr("Watching"), qsTr("Competing")]
                    Accessible.name: qsTr("Discord activity type")
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8

                SettingsFieldLabel {
                    text: qsTr("Member-list display")
                }

                SettingsSelect {
                    id: statusSelect
                    model: [qsTr("Platform"), qsTr("Details"), qsTr("State")]
                    Accessible.name: qsTr("Discord member-list display")
                }
            }

            Item {
                Layout.fillWidth: true
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 20

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4

                SettingsFieldLabel {
                    text: qsTr("Orchard project button")
                }

                SettingsHint {
                    text: qsTr("Add ‘View the Orchard Project’ as the optional second Discord button.")
                }
            }

            SettingsSwitch {
                id: projectSwitch
                Layout.alignment: Qt.AlignVCenter
                padding: 0
                checked: root.discord.projectButtonEnabled
                Accessible.name: qsTr("Show the Orchard project button")
            }
        }

        // Uploads go through the Orchard account worker, so no account means no moving pictures.
        RowLayout {
            Layout.fillWidth: true
            spacing: 20
            enabled: root.accountSignedIn
            opacity: enabled ? 1.0 : 0.46

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4

                SettingsFieldLabel {
                    text: qsTr("Animated artwork")
                }

                SettingsHint {
                    text: root.accountSignedIn
                        ? qsTr("Convert motion artwork to animated WebP and show it in Discord.")
                        : qsTr("Sign in to your Orchard account in General to show animated artwork in Discord.")
                }
            }

            SettingsSwitch {
                id: animatedSwitch
                Layout.alignment: Qt.AlignVCenter
                padding: 0
                checked: root.accountSignedIn && root.discord.animatedArtworkEnabled
                Accessible.name: qsTr("Show animated artwork in Discord")
            }
        }

        SettingsHint {
            text: !root.discord.enabled ? qsTr("Discord Rich Presence is off.")
                : root.discord.connected ? qsTr("Connected to Discord.")
                : root.discord.lastError ? qsTr("Discord is unavailable: %1").arg(root.discord.lastError)
                : qsTr("Connecting to Discord…")
            color: root.discord.lastError ? "#e6a197" : "#8d968e"
        }
    }
}
