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
    property real uiScale: 1
    property bool uiScaleRestartRequired: false
    property alias uiScaleSlider: uiScaleSlider
    property alias resetUiScaleButton: resetUiScaleButton
    property bool showBitrate: false
    property bool fullscreenWindowed: false
    property bool fullscreenAutoHide: true
    property bool fullscreenPulse: true
    property bool lyricsFocusBlur: true
    property bool lyricsDistanceFade: true
    property bool lyricsStaggeredScroll: true
    property bool artistVideoBackdrop: false
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
    property alias windowedSwitch: windowedSwitch
    property alias autoHideSwitch: autoHideSwitch
    property alias pulseSwitch: pulseSwitch
    property alias lyricsBlurSwitch: lyricsBlurSwitch
    property alias lyricsFadeSwitch: lyricsFadeSwitch
    property alias lyricsStaggerSwitch: lyricsStaggerSwitch
    property alias artistVideoSwitch: artistVideoSwitch
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

    ColorsSection {
        Layout.fillWidth: true
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Display scaling")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "monitor"
            title: qsTr("Interface scale")
            description: qsTr("Resize text, buttons, artwork, and menus together. Applied on top of your system display scaling. Restart Orchard to apply changes.")
        }

        SettingsRow {
            AppearanceSlider {
                id: uiScaleSlider
                objectName: "uiScaleControl"
                Layout.fillWidth: true
                label: qsTr("Interface scale")
                from: 0.75
                to: 2.5
                stepSize: 0.05
                value: root.uiScale
                displayValue: Math.round(root.uiScale * 100) + "%"
            }
        }

        SettingsRow {
            SettingsHint {
                Layout.fillWidth: true
                text: root.uiScaleRestartRequired
                    ? qsTr("Restart Orchard to apply the selected scale.")
                    : qsTr("100% follows your system display scaling.")
            }
            SettingsButton {
                id: resetUiScaleButton
                objectName: "resetUiScale"
                text: qsTr("Reset scale")
                enabled: root.uiScale !== 1
                Accessible.name: qsTr("Reset interface scale to 100%")
            }
        }
    }

    LyricsProvidersSection {
        Layout.fillWidth: true
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Layout")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "panel-bottom"
            title: qsTr("Player layout")
            description: root.layoutStyle === "canopy"
                ? qsTr("Canopy: a slim player bar at the top.")
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
            iconName: "app-window"
            title: qsTr("Keep player windowed")
            description: qsTr("Open the fullscreen player inside the app window, keeping its size and position.")

            SettingsSwitch {
                id: windowedSwitch
                objectName: "fullscreenWindowedToggle"
                checked: root.fullscreenWindowed
                Accessible.name: qsTr("Keep player windowed")
            }
        }

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
        title: qsTr("Lyrics")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "mic-vocal"
            title: qsTr("Blur distant lines")
            description: qsTr("Lines blur more the further they are from the line being sung. Uses extra GPU power.")

            SettingsSwitch {
                id: lyricsBlurSwitch
                objectName: "lyricsFocusBlurToggle"
                checked: root.lyricsFocusBlur
                Accessible.name: qsTr("Blur distant lines")
            }
        }

        SettingsRow {
            iconName: "moon"
            title: qsTr("Fade distant lines")
            description: qsTr("Lines dim the further they are from the line being sung.")

            SettingsSwitch {
                id: lyricsFadeSwitch
                objectName: "lyricsDistanceFadeToggle"
                checked: root.lyricsDistanceFade
                Accessible.name: qsTr("Fade distant lines")
            }
        }

        SettingsRow {
            iconName: "arrow-up-down"
            title: qsTr("Staggered scrolling")
            description: qsTr("Lines glide into place one after another when the lyrics advance.")

            SettingsSwitch {
                id: lyricsStaggerSwitch
                objectName: "lyricsStaggeredScrollToggle"
                checked: root.lyricsStaggeredScroll
                Accessible.name: qsTr("Staggered scrolling")
            }
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Artist pages")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "video"
            title: qsTr("Play a music video behind the artist")
            description: qsTr("Replace the artist photo at the top of artist pages with one of the artist's music videos, muted. The photo shows while the video loads, and artists without videos keep their photo. Uses extra network bandwidth.")

            SettingsSwitch {
                id: artistVideoSwitch
                objectName: "artistVideoBackdropToggle"
                checked: root.artistVideoBackdrop
                Accessible.name: qsTr("Play a music video behind the artist")
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
