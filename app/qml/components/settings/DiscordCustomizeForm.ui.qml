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

// Text templates and display options of the Discord popup.
ColumnLayout {
    id: root

    property var discord: ({
        activityText: "{song}",
        detailsText: "{artist}",
        stateText: ""
    })
    readonly property var platformValues: ["YouTube Music", "Orchard V3"]
    readonly property var activityValues: ["listening", "playing", "watching", "competing"]
    readonly property var statusDisplayValues: ["name", "details", "state"]
    property alias activityField: activityField
    property alias detailsField: detailsField
    property alias stateField: stateField
    property alias platformSelect: platformSelect
    property alias activitySelect: activitySelect
    property alias statusSelect: statusSelect

    spacing: 22

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
                model: [qsTr("YouTube Music / Apple Music"), qsTr("Orchard V3")]
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
}
