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
import QtQuick.Controls
import QtQuick.Layouts

// If you're looking for the secret button that fixes all bugs, it's not here.
// But you CAN admire the licenses we bundled so the lawyers stay happy.
ColumnLayout {
    id: root

    property alias orchardLinkButton: orchardLinkButton
    property alias orchardLicenseButton: orchardLicenseButton
    property alias licenseList: licenseList
    property alias releaseNotesButton: releaseNotesButton
    property string appVersion: ""
    property var releaseNotes: []
    property string releaseSummary: ""

    spacing: 28
    Layout.fillWidth: true

    // Open source license list bundled with Orchard.
    readonly property var thirdPartyLicenses: [
        {
            key: "lucide",
            name: "Lucide Icons",
            license: "ISC / MIT",
            description: qsTr("Clean, consistent iconography selected from Lucide Static 1.44.0 and Feather Icons."),
            url: "https://lucide.dev"
        },
        {
            key: "quickjs",
            name: "QuickJS",
            license: "MIT",
            description: qsTr("Small and embeddable Javascript engine executing extracted YouTube provider bytecode."),
            url: "https://github.com/quickjs-ng/quickjs"
        },
        {
            key: "qtkeychain",
            name: "QtKeychain",
            license: "BSD-3-Clause",
            description: qsTr("Platform-native secure credential and keychain storage for YouTube sessions."),
            url: "https://github.com/frankosterfeld/qtkeychain"
        },
        {
            key: "kawarp",
            name: "kawarp",
            license: "MIT",
            description: qsTr("Fluid dynamic mesh distortion and background canvas shader effects by Better Lyrics."),
            url: "https://github.com/Better-Lyrics/kawarp"
        },
        {
            key: "oxc",
            name: "Oxc",
            license: "MIT",
            description: qsTr("High-performance JavaScript parser and lexical analyzer used in native player extraction."),
            url: "https://oxc.rs"
        },
        {
            key: "youtubejs",
            name: "YouTube.js",
            license: "MIT",
            description: qsTr("Player matchers, built-in definitions, and extractor policy algorithms by LuanRT."),
            url: "https://github.com/LuanRT/YouTube.js"
        },
        {
            key: "inter",
            name: "Inter Font",
            license: "OFL-1.1",
            description: qsTr("Carefully crafted UI typeface designed by Rasmus Andersson and the Inter Project authors."),
            url: "https://rsms.me/inter/"
        },
        {
            key: "libdatachannel",
            name: "libdatachannel",
            license: "MPL-2.0",
            description: qsTr("WebSocket and WebRTC data channels that carry Orchard Connect between your devices."),
            url: "https://github.com/paullouisageneau/libdatachannel"
        },
        {
            key: "libjuice",
            name: "libjuice",
            license: "MPL-2.0",
            description: qsTr("ICE connectivity that finds a path between devices on different networks."),
            url: "https://github.com/paullouisageneau/libjuice"
        },
        {
            key: "usrsctp",
            name: "usrsctp",
            license: "BSD-3-Clause",
            description: qsTr("SCTP stack under the WebRTC data channel."),
            url: "https://github.com/sctplab/usrsctp"
        },
        {
            key: "plog",
            name: "plog",
            license: "MIT",
            description: qsTr("Logging inside libdatachannel."),
            url: "https://github.com/SergiusTheBest/plog"
        },
        {
            key: "mbedtls",
            name: "Mbed TLS",
            license: "Apache-2.0",
            description: qsTr("DTLS for data channels and the session encryption behind Orchard Connect."),
            url: "https://github.com/Mbed-TLS/mbedtls"
        },
        {
            key: "nlohmann-json",
            name: "JSON for Modern C++",
            license: "MIT",
            description: qsTr("JSON messages in the Orchard Connect core, by Niels Lohmann."),
            url: "https://github.com/nlohmann/json"
        },
        {
            key: "fidelityfx-fsr",
            name: "AMD FidelityFX Super Resolution 1",
            license: "MIT",
            description: qsTr("Upscaling and sharpening for music videos smaller than the screen."),
            url: "https://github.com/GPUOpen-Effects/FidelityFX-FSR"
        }
    ]

    // Project header and Orchard license
    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("About Orchard")
        rowSpacing: 0
        verticalPadding: 0

        Item {
            Layout.fillWidth: true
            implicitHeight: heroLayout.implicitHeight + 40

            RowLayout {
                id: heroLayout
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                spacing: 18

                // Hey! That's me!
                Rectangle {
                    Layout.preferredWidth: 72
                    Layout.preferredHeight: 72
                    radius: 18
                    color: "#12ffffff"
                    border.color: "#1cffffff"
                    clip: true

                    AnimatedImage {
                        anchors.fill: parent
                        anchors.margins: 1
                        source: "qrc:/qt/qml/Orchard/app/qml/assets/sfg545.gif"
                        fillMode: Image.PreserveAspectCrop
                        playing: true
                        smooth: true
                        mipmap: true
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 4

                    Text {
                        text: qsTr("Orchard")
                        color: "#f0eee7"
                        font.family: "Inter"
                        font.pixelSize: 22
                        font.weight: Font.Bold
                    }

                    Text {
                        Layout.fillWidth: true
                        text: qsTr("A native Qt 6 / QML music player, made by SFG545")
                        color: "#a3ada5"
                        font.family: "Inter"
                        font.pixelSize: 13
                        wrapMode: Text.WordWrap
                    }
                }

                // Version pill
                Rectangle {
                    Layout.alignment: Qt.AlignVCenter
                    radius: 8
                    color: "#12ffffff"
                    border.color: "#1cffffff"
                    implicitHeight: 28
                    implicitWidth: versionText.implicitWidth + 20

                    Text {
                        id: versionText
                        anchors.centerIn: parent
                        text: qsTr("Version %1").arg(root.appVersion)
                        color: "#d6d9d2"
                        font.family: "Inter"
                        font.pixelSize: 12
                        font.weight: Font.Medium
                    }
                }
            }
        }

        SettingsRow {
            iconName: "book-open"
            title: qsTr("Orchard License")
            description: qsTr("Copyright (C) 2026 SFG545. Free software under the GNU Affero General Public License v3 or later.")

            SettingsButton {
                id: orchardLinkButton
                text: qsTr("Website")
            }

            SettingsButton {
                id: orchardLicenseButton
                text: qsTr("License")
            }
        }
    }

    // The history opens in its own window; bundled notes read offline.
    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Release Notes")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "sparkles"
            title: qsTr("What's new")
            description: root.releaseSummary

            SettingsButton {
                id: releaseNotesButton
                text: qsTr("View release notes")
                enabled: root.releaseNotes.length > 0
            }
        }
    }

    // --- Third-Party Open Source Notices ---
    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Third-Party Licenses")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "heart"
            title: qsTr("Built on open source")
            description: qsTr("Orchard bundles the following projects, each used under its own license terms. Thank you to everyone behind them.")
        }

        // The logic layer swaps in a delegate whose buttons open the link and license text.
        Repeater {
            id: licenseList
            model: root.thirdPartyLicenses

            delegate: SettingsLicenseCard {
                required property var modelData
                name: modelData.name
                license: modelData.license
                blurb: modelData.description
            }
        }
    }
}
