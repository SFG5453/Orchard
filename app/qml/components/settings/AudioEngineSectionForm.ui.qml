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

SettingsSection {
    id: root

    property var engine: ({
        enabled: true,
        autoEqEnabled: true,
        eqEnabled: true,
        spectrum: [0.2, 0.5, 0.7, 0.6, 0.4, 0.5, 0.3, 0.2, 0.1, 0.1],
        gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        activePreset: "flat",
        outputDevices: [],
        outputDeviceId: "",
        normalizationEnabled: true
    })
    // Engine and adaptive mix are mutually exclusive.
    property bool adaptiveMixOn: false
    property bool trackLoaded: true
    // Display strings for the sliders, formatted by the logic layer.
    property var bandTexts: ["0.0 dB", "0.0 dB", "0.0 dB", "0.0 dB", "0.0 dB", "0.0 dB", "0.0 dB", "0.0 dB", "0.0 dB", "0.0 dB"]
    property string preampText: "0.0 dB"
    property string qText: "Q 1.0"
    property string balanceText: qsTr("Center")
    property string outputGainText: "0.0 dB"
    property string trackGainText: "0.0 dB"
    property real preampDb: 0
    property real q: 1
    property real balance: 0
    property real outputGainDb: 0
    property real trackGainDb: 0
    readonly property var bands: [
        { label: "31 Hz", index: 0 },
        { label: "62 Hz", index: 1 },
        { label: "125 Hz", index: 2 },
        { label: "250 Hz", index: 3 },
        { label: "500 Hz", index: 4 },
        { label: "1 kHz", index: 5 },
        { label: "2 kHz", index: 6 },
        { label: "4 kHz", index: 7 },
        { label: "8 kHz", index: 8 },
        { label: "16 kHz", index: 9 }
    ]
    readonly property var presets: [
        { label: qsTr("Flat"), value: "flat" },
        { label: qsTr("Bass boost"), value: "bass" },
        { label: qsTr("Electronic"), value: "electronic" },
        { label: qsTr("Rock"), value: "rock" },
        { label: qsTr("Vocal"), value: "vocal" },
        { label: qsTr("Acoustic"), value: "acoustic" },
        { label: qsTr("Bright"), value: "bright" }
    ]

    property alias engineSwitch: engineSwitch
    property alias autoEqSwitch: autoEqSwitch
    property alias eqSwitch: eqSwitch
    property alias presetList: presetList
    property alias bandList: bandList
    property alias preampSlider: preampSlider
    property alias qSlider: qSlider
    property alias balanceSlider: balanceSlider
    property alias gainSlider: gainSlider
    property alias levelingSwitch: levelingSwitch
    property alias trackGainSlider: trackGainSlider
    property alias outputDevice: outputDevice
    property alias resetButton: resetButton

    title: qsTr("Audio Engine")
    rowSpacing: 0
    verticalPadding: 0

    SettingsRow {
        iconName: "sliders-horizontal"
        title: qsTr("Audio Engine")
        description: root.adaptiveMixOn && !root.engine.enabled
                     ? qsTr("Unavailable while adaptive mix is on. Switch crossfade to Standard to use it.")
                     : qsTr("Turn on equalizer, balance, and volume leveling. When off, audio plays untouched.")

        SettingsSwitch {
            id: engineSwitch
            checked: root.engine.enabled
            // Turning off stays allowed so a stale both-on state can be cleared.
            enabled: checked || !root.adaptiveMixOn
            Accessible.name: qsTr("Audio Engine")
        }
    }

    SettingsRow {
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 112
            radius: 12
            color: "#14ffffff"
            border.color: "#1cffffff"
            clip: true

            Row {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.top: status.bottom
                anchors.margins: 12
                spacing: 4

                Repeater {
                    model: 10

                    Rectangle {
                        required property int index
                        width: Math.max(2, (parent.width - 36) / 10)
                        height: Math.max(2, +(root.engine.spectrum[index] || 0) * parent.height)
                        anchors.bottom: parent.bottom
                        radius: 2
                        color: "#668f74"
                        opacity: root.engine.enabled ? 0.78 : 0.28
                    }
                }
            }

            Text {
                id: status
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.margins: 12
                text: !root.engine.enabled ? qsTr("Bypassed")
                      : root.engine.autoEqEnabled ? qsTr("Automatic EQ active")
                      : root.engine.eqEnabled ? qsTr("Manual EQ active")
                      : qsTr("Direct")
                color: "#c4e0cb"
                font.family: "Inter"
                font.pixelSize: 12
                font.weight: Font.Medium
            }
        }
    }

    SettingsRow {
        enabled: root.engine.enabled
        iconName: "sparkles"
        title: qsTr("Automatic EQ")
        description: qsTr("Gently balance bass, mids, and treble as each track plays.")

        SettingsSwitch {
            id: autoEqSwitch
            checked: root.engine.autoEqEnabled
            Accessible.name: qsTr("Automatic EQ")
        }
    }

    SettingsRow {
        enabled: root.engine.enabled
        iconName: "sliders-horizontal"
        title: qsTr("Manual equalizer")
        description: qsTr("Use a preset or shape the ten-band curve yourself.")

        SettingsSwitch {
            id: eqSwitch
            checked: root.engine.eqEnabled
            Accessible.name: qsTr("Manual equalizer")
        }
    }

    SettingsRow {
        enabled: root.engine.enabled && root.engine.eqEnabled

        GridLayout {
            Layout.fillWidth: true
            columns: 4
            rowSpacing: 8
            columnSpacing: 8

            // The logic layer swaps in a delegate that applies the preset.
            Repeater {
                id: presetList
                model: root.presets

                SettingsButton {
                    required property var modelData
                    Layout.fillWidth: true
                    text: modelData.label
                    highlighted: root.engine.activePreset === modelData.value
                }
            }
        }
    }

    SettingsRow {
        enabled: root.engine.enabled && root.engine.eqEnabled

        GridLayout {
            Layout.fillWidth: true
            columns: 2
            rowSpacing: 12
            columnSpacing: 20

            // The logic layer swaps in a delegate that writes the band gain.
            Repeater {
                id: bandList
                model: root.bands

                AppearanceSlider {
                    required property var modelData
                    Layout.fillWidth: true
                    label: modelData.label
                    displayValue: root.bandTexts[modelData.index] || ""
                    value: +(root.engine.gains[modelData.index] || 0)
                    from: -12
                    to: 12
                    stepSize: 0.5
                }
            }
        }
    }

    SettingsRow {
        GridLayout {
            Layout.fillWidth: true
            columns: 2
            rowSpacing: 12
            columnSpacing: 20

            AppearanceSlider {
                id: preampSlider
                Layout.fillWidth: true
                enabled: root.engine.enabled && root.engine.eqEnabled
                label: qsTr("EQ preamp")
                displayValue: root.preampText
                value: root.preampDb
                from: -12
                to: 6
                stepSize: 0.5
            }

            AppearanceSlider {
                id: qSlider
                Layout.fillWidth: true
                enabled: root.engine.enabled
                label: qsTr("Band width")
                displayValue: root.qText
                value: root.q
                from: 0.4
                to: 2.4
                stepSize: 0.1
            }

            AppearanceSlider {
                id: balanceSlider
                Layout.fillWidth: true
                enabled: root.engine.enabled
                label: qsTr("Balance")
                displayValue: root.balanceText
                value: root.balance
                from: -1
                to: 1
                stepSize: 0.05
            }

            AppearanceSlider {
                id: gainSlider
                Layout.fillWidth: true
                label: qsTr("Global gain")
                displayValue: root.outputGainText
                value: root.outputGainDb
                from: -24
                to: 6
                stepSize: 0.5
            }
        }
    }

    SettingsRow {
        iconName: "volume-2"
        title: qsTr("Dynamic leveling")
        description: qsTr("Reduce sudden volume jumps and control loud peaks.")

        SettingsSwitch {
            id: levelingSwitch
            checked: root.engine.normalizationEnabled
            Accessible.name: qsTr("Dynamic leveling")
        }
    }

    SettingsRow {
        enabled: root.engine.enabled && root.trackLoaded

        AppearanceSlider {
            id: trackGainSlider
            Layout.fillWidth: true
            label: qsTr("Track gain")
            displayValue: root.trackGainText
            value: root.trackGainDb
            from: -12
            to: 12
            stepSize: 0.5
        }
    }

    SettingsRow {
        iconName: "music-2"
        title: qsTr("Output device")
        description: qsTr("Route Orchard to a specific system audio output.")

        SettingsComboBox {
            id: outputDevice
            Layout.preferredWidth: 220
            model: root.engine.outputDevices
            textRole: "label"
            valueRole: "id"
            Accessible.name: qsTr("Output device")
        }
    }

    SettingsRow {
        Item { Layout.fillWidth: true }

        SettingsButton {
            id: resetButton
            text: qsTr("Reset engine")
        }
    }
}
