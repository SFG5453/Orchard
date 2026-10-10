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

// Adjust audio fidelity here. Yes, your gold-plated HDMI cable still won't help.
ColumnLayout {
    id: root

    property string streamQuality: "high"
    property bool qobuzReachable: false
    property bool crossfadeEnabled: true
    property string adaptiveMode: "standard"
    property real crossfadeDuration: 6
    property bool gaplessEnabled: true
    property bool exponentialVolumeEnabled: false
    property bool autoplayEnabled: true
    property string slopAction: "mark"
    property string nonMusicSkipMode: "button"
    property string queueLayout: "upNext"
    property alias streamQualityPicker: streamQualityPicker
    property alias crossfadeSwitch: crossfadeSwitch
    property alias crossfadeLength: crossfadeLength
    property alias gaplessSwitch: gaplessSwitch
    property alias exponentialVolumeSwitch: exponentialVolumeSwitch
    property alias autoplaySwitch: autoplaySwitch
    property alias slopPicker: slopPicker
    property alias nonMusicPicker: nonMusicPicker
    property alias queueLayoutPicker: queueLayoutPicker

    spacing: 28
    Layout.fillWidth: true

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Quality")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "audio-lines"
            title: qsTr("Streaming quality")
            description: (root.streamQuality === "saver" ? qsTr("Lowest bitrate audio and 480p video.")
                : root.streamQuality === "normal" ? qsTr("Up to 128 kbps audio and 720p video.")
                : root.streamQuality === "max" ? qsTr("Lossless and Hi-Res from your Qobuz subscription, up to 24-bit/192 kHz. Songs Qobuz can't match play at High.")
                : qsTr("Best available audio and video."))
                + (root.qobuzReachable ? "" : " " + qsTr("MAX needs a Qobuz account. Connect one in Settings, Integrations."))

            SettingsSegmented {
                id: streamQualityPicker
                currentValue: root.streamQuality
                model: [
                    { value: "saver", label: qsTr("Saver"), name: qsTr("Saver quality") },
                    { value: "normal", label: qsTr("Normal"), name: qsTr("Normal quality") },
                    { value: "high", label: qsTr("High"), name: qsTr("High quality") },
                    // Members only: the velvet rope is a Qobuz login.
                    {
                        value: "max", label: qsTr("MAX"), name: qsTr("MAX quality from Qobuz"),
                        // A connected phone's Qobuz sign-in counts too; Connect streams its bytes here.
                        enabled: root.qobuzReachable,
                        tip: qsTr("Connect Qobuz in Settings, Integrations to use MAX.")
                    }
                ]
            }
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Volume")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "volume-2"
            title: qsTr("Exponential volume")
            description: qsTr("Make the volume slider more precise at low volumes.")

            SettingsSwitch {
                id: exponentialVolumeSwitch
                objectName: "settingsExponentialVolume"
                checked: root.exponentialVolumeEnabled
                Accessible.name: qsTr("Exponential volume")
            }
        }
    }

    AudioEngineSection {
        Layout.fillWidth: true
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Transitions")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "repeat"
            title: qsTr("Crossfade")
            description: qsTr("Blend the end of one track into the next.")

            SettingsSwitch {
                id: crossfadeSwitch
                objectName: "settingsCrossfade"
                checked: root.crossfadeEnabled
                Accessible.name: qsTr("Crossfade")
            }
        }

        CrossfadeMode { }

        SettingsRow {
            visible: root.adaptiveMode === "standard"
            enabled: root.crossfadeEnabled

            AppearanceSlider {
                id: crossfadeLength
                Layout.fillWidth: true
                label: qsTr("Crossfade length")
                displayValue: qsTr("%1 seconds").arg(Math.round(value))
                value: root.crossfadeDuration
                from: 1
                to: 12
                stepSize: 1
            }
        }

        SettingsRow {
            iconName: "skip-forward"
            title: qsTr("Gapless playback")
            description: qsTr("Preload the next song to minimize pauses between tracks.")

            SettingsSwitch {
                id: gaplessSwitch
                objectName: "settingsGapless playback"
                checked: root.gaplessEnabled
                Accessible.name: qsTr("Gapless playback")
            }
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Queue")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "infinity"
            title: qsTr("Autoplay")
            description: qsTr("Keep the music going with similar songs when your queue runs out.")

            SettingsSwitch {
                id: autoplaySwitch
                objectName: "settingsAutoplay"
                checked: root.autoplayEnabled
                Accessible.name: qsTr("Autoplay")
            }
        }

        SettingsRow {
            iconName: "list-music"
            title: qsTr("Queue style")
            description: qsTr("Up next lists only what is still queued. Continuous also shows what already played, with the current song in place.")

            SettingsSegmented {
                id: queueLayoutPicker
                currentValue: root.queueLayout
                model: [
                    { value: "upNext", label: qsTr("Up next"), name: qsTr("Queue style: %1").arg(qsTr("Up next")) },
                    { value: "continuous", label: qsTr("Continuous"), name: qsTr("Queue style: %1").arg(qsTr("Continuous")) }
                ]
            }
        }

        // Skynet may compose, but it doesn't get the aux cord.
        SettingsRow {
            iconName: "sparkles"
            title: qsTr("When a track sounds AI-generated")
            description: (root.slopAction === "off" ? qsTr("Tracks are not analysed.")
                : root.slopAction === "skip" ? qsTr("Mark it and skip it when it comes up.")
                : root.slopAction === "remove" ? qsTr("Mark it, skip it, and remove known AI tracks from the queue.")
                : qsTr("Mark it with an AI badge."))
                + " " + qsTr("Upcoming songs are downloaded at low bitrate and analysed in the background. It catches fully generated songs, not AI voice covers.")

            SettingsSegmented {
                id: slopPicker
                currentValue: root.slopAction
                model: [
                    { value: "off", label: qsTr("Off"), name: qsTr("AI-generated music: %1").arg(qsTr("Off")) },
                    { value: "mark", label: qsTr("Mark"), name: qsTr("AI-generated music: %1").arg(qsTr("Mark")) },
                    { value: "skip", label: qsTr("Skip"), name: qsTr("AI-generated music: %1").arg(qsTr("Skip")) },
                    { value: "remove", label: qsTr("Remove"), name: qsTr("AI-generated music: %1").arg(qsTr("Remove")) }
                ]
            }
        }

        SettingsRow {
            iconName: "skip-forward"
            title: qsTr("Non-music parts")
            description: (root.nonMusicSkipMode === "off" ? qsTr("Songs play through, talking and all.")
                : root.nonMusicSkipMode === "auto" ? qsTr("Skip talking intros, skits and applause automatically.")
                : qsTr("Show a Skip button over talking intros, skits and applause."))
                + " " + qsTr("Sections come from SponsorBlock volunteers. Lyrics stay in sync because skipping just seeks the song.")

            SettingsSegmented {
                id: nonMusicPicker
                currentValue: root.nonMusicSkipMode
                model: [
                    { value: "off", label: qsTr("Off"), name: qsTr("Non-music parts: %1").arg(qsTr("Off")) },
                    { value: "button", label: qsTr("Button"), name: qsTr("Non-music parts: %1").arg(qsTr("Skip button")) },
                    { value: "auto", label: qsTr("Auto skip"), name: qsTr("Non-music parts: %1").arg(qsTr("Auto skip")) }
                ]
            }
        }
    }
}
