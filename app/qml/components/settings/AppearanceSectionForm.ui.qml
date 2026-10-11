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

// Because you can never have too many aesthetic toggles to fiddle with while pretending to study.
ColumnLayout {
    id: root

    property string layoutStyle: "glade"
    property bool showBitrate: false
    property bool fullscreenAutoHide: true
    property bool fullscreenPulse: true
    property bool animatedArtworkEnabled: true
    property bool animatedCollageEnabled: false
    property string artworkSource: "apple_music"
    property bool immersiveBackground: true
    property real speed: 1
    property real intensity: 0.8
    property real saturation: 1
    property real brightness: 1
    // Slider caption for speed, formatted by the logic layer.
    property string speedText: "1.00×"
    property alias layoutPicker: layoutPicker
    property alias bitrateSwitch: bitrateSwitch
    property alias autoHideSwitch: autoHideSwitch
    property alias pulseSwitch: pulseSwitch
    property alias animatedArtworkSwitch: animatedArtworkSwitch
    property alias collageSwitch: collageSwitch
    property alias sourcePicker: sourcePicker
    property alias resetOrderButton: resetOrderButton
    property alias immersiveSwitch: immersiveSwitch
    property alias speedSlider: speedSlider
    property alias intensitySlider: intensitySlider
    property alias saturationSlider: saturationSlider
    property alias brightnessSlider: brightnessSlider
    property alias resetControlsButton: resetControlsButton

    spacing: 28
    Layout.fillWidth: true

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Layout")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "panel-bottom"
            title: qsTr("Player layout")
            description: root.layoutStyle === "canopy"
                ? qsTr("Canopy: a slim player bar at the top, next to search.")
                : qsTr("Glade: a floating player bar at the bottom.")

            SettingsSegmented {
                id: layoutPicker
                objectName: "layoutStyleSegmented"
                currentValue: root.layoutStyle
                model: [
                    { value: "glade", label: qsTr("Glade"), name: qsTr("Glade layout") },
                    { value: "canopy", label: qsTr("Canopy"), name: qsTr("Canopy layout") }
                ]
            }
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Player bar")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "audio-lines"
            title: qsTr("Show audio bitrate")
            description: qsTr("Display streaming bitrate indicator in the player bar.")

            SettingsSwitch {
                id: bitrateSwitch
                objectName: "bitrateToggle"
                checked: root.showBitrate
                Accessible.name: qsTr("Show audio bitrate")
            }
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Fullscreen player")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "maximize"
            title: qsTr("Hide controls when idle")
            description: qsTr("Fade the controls and cursor after a few seconds without mouse movement while music plays. Move the mouse or press a key to bring them back.")

            SettingsSwitch {
                id: autoHideSwitch
                objectName: "fullscreenAutoHideToggle"
                checked: root.fullscreenAutoHide
                Accessible.name: qsTr("Hide controls when idle")
            }
        }

        SettingsRow {
            iconName: "sparkles"
            title: qsTr("Pulse with the music")
            description: qsTr("The glow behind the cover and the background brighten on each bass hit.")

            SettingsSwitch {
                id: pulseSwitch
                objectName: "fullscreenPulseToggle"
                checked: root.fullscreenPulse
                Accessible.name: qsTr("Pulse with the music")
            }
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Animated artwork")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "play"
            title: qsTr("Enable animated artwork")
            description: qsTr("Stream moving cover artwork for supported songs and albums. Consumes additional network bandwidth.")

            SettingsSwitch {
                id: animatedArtworkSwitch
                objectName: "animatedArtworkToggle"
                checked: root.animatedArtworkEnabled
                Accessible.name: qsTr("Enable animated artwork")
            }
        }

        SettingsRow {
            enabled: root.animatedArtworkEnabled
            iconName: "layout-grid"
            title: qsTr("Animate playlist collages")
            description: qsTr("Play motion artwork inside the four-cover collage on playlist pages. This can be system intensive: up to four videos play at once, using extra CPU, GPU and network bandwidth.")

            SettingsSwitch {
                id: collageSwitch
                objectName: "animatedCollageToggle"
                checked: root.animatedCollageEnabled
                Accessible.name: qsTr("Animate playlist collages")
            }
        }

        SettingsRow {
            enabled: root.animatedArtworkEnabled
            iconName: "compass"
            title: qsTr("Artwork source")
            description: qsTr("Where motion artwork is fetched from.")

            SettingsSegmented {
                id: sourcePicker
                currentValue: root.artworkSource
                model: [{ value: "apple_music", label: qsTr("Apple Music"), name: qsTr("Apple Music source") }]
            }
        }

        SettingsRow {
            enabled: root.animatedArtworkEnabled
            iconName: "list-music"
            title: qsTr("Mirror query order")
            description: qsTr("Mirrors are tried top to bottom. If one has no motion art or times out, the next is used.")

            SettingsButton {
                id: resetOrderButton
                text: qsTr("Reset order")
                Accessible.name: qsTr("Reset mirror order")
            }
        }

        SettingsRow {
            enabled: root.animatedArtworkEnabled

            MirrorOrderList {
                Layout.fillWidth: true
            }
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Immersive background")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "palette"
            title: qsTr("Use artwork background")
            description: qsTr("Flowing colors from the current track’s artwork. Motion pauses with playback. Set speed to zero for a still background.")

            SettingsSwitch {
                id: immersiveSwitch
                objectName: "immersiveToggle"
                checked: root.immersiveBackground
                Accessible.name: qsTr("Use artwork background")
            }
        }

        SettingsRow {
            enabled: root.immersiveBackground

            GridLayout {
                Layout.fillWidth: true
                columns: 2
                rowSpacing: 16
                columnSpacing: 24

                AppearanceSlider {
                    id: speedSlider
                    Layout.fillWidth: true
                    objectName: "speedControl"
                    label: qsTr("Speed")
                    to: 5
                    value: root.speed
                    displayValue: root.speedText
                }
                AppearanceSlider {
                    id: intensitySlider
                    Layout.fillWidth: true
                    objectName: "intensityControl"
                    label: qsTr("Intensity")
                    value: root.intensity
                    displayValue: Math.round(value * 100) + "%"
                }
                AppearanceSlider {
                    id: saturationSlider
                    Layout.fillWidth: true
                    objectName: "saturationControl"
                    label: qsTr("Saturation")
                    to: 3
                    value: root.saturation
                    displayValue: Math.round(value * 100) + "%"
                }
                AppearanceSlider {
                    id: brightnessSlider
                    Layout.fillWidth: true
                    objectName: "brightnessControl"
                    label: qsTr("Brightness")
                    to: 2
                    value: root.brightness
                    displayValue: Math.round(value * 100) + "%"
                }
            }
        }

        SettingsRow {
            Item { Layout.fillWidth: true }

            SettingsButton {
                id: resetControlsButton
                objectName: "resetControls"
                text: qsTr("Reset controls")
            }
        }
    }
}
