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

// Offline listening: download quality, animated covers, storage, and lyric translation models.
ColumnLayout {
    id: root

    property string quality: "high"
    property bool animatedArtwork: false
    // Animated artwork's own setting in Appearance.
    property bool animatedArtworkAppearanceOn: true
    property int downloadCount: 0
    property string countTitle: qsTr("%1 downloaded songs").arg(0)
    property string usedText: ""
    property int pending: 0
    property int failedCount: 0
    property string currentTitle: ""
    property real progress: 0
    property bool offline: false
    property alias qualityPicker: qualityPicker
    property alias animatedSwitch: animatedSwitch
    property alias clearButton: clearButton
    property alias retryButton: retryButton

    spacing: 28
    Layout.fillWidth: true

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Quality")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "download"
            title: qsTr("Download quality")
            description: (root.quality === "saver" ? qsTr("Smallest files: the lowest bitrate audio.")
                : root.quality === "normal" ? qsTr("Up to 128 kbps audio.")
                : qsTr("Best available audio."))
                + " " + qsTr("Applies to future downloads. Songs already saved keep their quality.")

            SettingsSegmented {
                id: qualityPicker
                currentValue: root.quality
                model: [
                    { value: "saver", label: qsTr("Saver"), name: qsTr("Saver download quality") },
                    { value: "normal", label: qsTr("Normal"), name: qsTr("Normal download quality") },
                    { value: "high", label: qsTr("High"), name: qsTr("High download quality") }
                ]
            }
        }

        SettingsRow {
            iconName: "sparkles"
            title: qsTr("Download animated artwork")
            description: qsTr("Also save the looping cover video when a song has one. These videos are large, often several megabytes for each album, so this uses a lot more bandwidth and storage than audio alone.")
                + (root.animatedArtworkAppearanceOn ? ""
                    : " " + qsTr("Animated artwork is off in Appearance, so nothing is saved until you turn it on."))

            SettingsSwitch {
                id: animatedSwitch
                objectName: "settingsDownloadAnimatedArtwork"
                checked: root.animatedArtwork
                Accessible.name: qsTr("Download animated artwork")
            }
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Storage")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "hard-drive"
            title: root.countTitle
            description: root.downloadCount > 0
                ? qsTr("Using %1 on this computer.").arg(root.usedText)
                : qsTr("Download songs, playlists and albums from their menus to listen without a connection.")

            SettingsButton {
                id: clearButton
                text: qsTr("Clear all downloads")
                enabled: root.downloadCount > 0 || root.pending > 0
                Accessible.name: qsTr("Clear all downloads")
            }
        }

        SettingsRow {
            visible: root.pending > 0 || root.failedCount > 0
            iconName: "arrow-down"
            title: root.pending > 0
                ? qsTr("Downloading %1").arg(root.currentTitle || qsTr("songs"))
                : qsTr("Some downloads did not finish")
            description: root.pending > 0
                ? qsTr("%n song(s) left, %1% of the queue done.", "", root.pending).arg(Math.round(root.progress * 100))
                : qsTr("%n song(s) failed. They resume when you retry.", "", root.failedCount)

            SettingsButton {
                id: retryButton
                visible: root.failedCount > 0
                text: qsTr("Retry")
                enabled: !root.offline
            }
        }
    }

    LyricTranslationSettings {
        Layout.fillWidth: true
    }
}
