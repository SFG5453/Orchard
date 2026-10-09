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
import ".."

// Sidebar, header and scrolling page of the settings popup.
RowLayout {
    id: root

    // Nav entries; key doubles as the section id used by the loader switch.
    readonly property var sections: [
        { key: "general", label: qsTr("General"), icon: "sliders-horizontal", blurb: qsTr("Startup behavior and your account.") },
        { key: "playback", label: qsTr("Playback"), icon: "audio-lines", blurb: qsTr("Quality, audio engine, and transitions between songs.") },
        { key: "downloads", label: qsTr("Downloads"), icon: "download", blurb: qsTr("Offline listening, download quality, storage, and lyric translation models.") },
        { key: "appearance", label: qsTr("Appearance"), icon: "palette", blurb: qsTr("Artwork, backgrounds, and player bar details.") },
        { key: "integrations", label: qsTr("Integrations"), icon: "plug", blurb: qsTr("Connect Orchard to other apps.") },
        { key: "shortcuts", label: qsTr("Shortcuts"), icon: "keyboard", blurb: qsTr("Keyboard shortcuts for playback and navigation.") },
        { key: "about", label: qsTr("About"), icon: "info", blurb: qsTr("Version, release notes, credits, and open source licenses.") }
    ]
    property int currentIndex: 0
    readonly property var currentSection: sections[currentIndex]
    readonly property string activeSection: currentSection.key
    property alias navList: navList
    property alias docsLink: docsLink
    property alias supportLink: supportLink
    property alias closeButton: closeButton
    property alias headerFade: headerFade
    property alias scroller: scroller
    property alias sectionLoader: sectionLoader

    spacing: 0

    ColumnLayout {
        Layout.preferredWidth: 216
        Layout.fillHeight: true
        Layout.margins: 20
        Layout.rightMargin: 16
        spacing: 2

        Text {
            text: qsTr("Settings")
            color: "#8d968e"
            font.family: "Inter"
            font.pixelSize: 12
            font.weight: Font.DemiBold
            font.letterSpacing: 0.6
            font.capitalization: Font.AllUppercase
            Layout.leftMargin: 12
            Layout.topMargin: 6
            Layout.bottomMargin: 12
        }

        Item {
            Layout.fillWidth: true
            implicitHeight: navColumn.implicitHeight

            // One pill that glides between entries; the nav's own little curling stone.
            Rectangle {
                width: parent.width
                height: 40
                radius: 10
                // Translucent so the glass shows through the selection.
                gradient: Gradient {
                    GradientStop { position: 0.0; color: "#338cc4a0" }
                    GradientStop { position: 1.0; color: "#1a8cc4a0" }
                }
                border.color: "#38a0d6b4"
                y: root.currentIndex * (height + navColumn.spacing)
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
            }

            Column {
                id: navColumn
                width: parent.width
                spacing: 2

                // The logic layer swaps in a delegate that selects the section on click.
                Repeater {
                    id: navList
                    model: root.sections

                    delegate: SettingsNavItem {
                        required property var modelData
                        required property int index
                        width: navColumn.width
                        entry: modelData
                        current: root.currentIndex === index
                    }
                }
            }
        }

        Item {
            Layout.fillHeight: true
        }

        // Launcher, not a section: it opens the docs popup.
        SettingsLinkItem {
            id: docsLink
            Layout.fillWidth: true
            glyph: "book-open"
            label: qsTr("Docs")
        }

        SettingsLinkItem {
            id: supportLink
            Layout.fillWidth: true
            glyph: "bug"
            label: qsTr("Report a bug")
        }
    }

    Rectangle {
        Layout.fillHeight: true
        Layout.preferredWidth: 1
        color: "#1cffffff"
    }

    ColumnLayout {
        Layout.fillWidth: true
        Layout.fillHeight: true
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 26
            Layout.leftMargin: 36
            Layout.rightMargin: 20
            Layout.bottomMargin: 18
            spacing: 16

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 6

                NumberAnimation on opacity {
                    id: headerFade
                    from: 0
                    to: 1
                    duration: 220
                    easing.type: Easing.OutCubic
                }

                Text {
                    Layout.fillWidth: true
                    text: root.currentSection.label
                    color: "#f0eee7"
                    font.family: "Inter"
                    font.pixelSize: 26
                    font.weight: Font.DemiBold
                }

                Text {
                    Layout.fillWidth: true
                    text: root.currentSection.blurb
                    color: "#a3ada5"
                    font.family: "Inter"
                    font.pixelSize: 13
                    wrapMode: Text.WordWrap
                }
            }

            RoundButton {
                id: closeButton
                Layout.alignment: Qt.AlignTop
                implicitWidth: 34
                implicitHeight: 34
                hoverEnabled: true
                Accessible.name: qsTr("Close settings")

                contentItem: Item {
                    LucideIcon {
                        anchors.centerIn: parent
                        width: 16
                        height: 16
                        name: "x"
                        color: "#f0eee7"
                    }
                }

                background: Rectangle {
                    radius: width / 2
                    color: closeButton.down ? "#30ffffff" : closeButton.hovered ? "#20ffffff" : "#10ffffff"
                    border.color: closeButton.visualFocus ? "#a6d4bf" : "transparent"
                }
            }
        }

        ScrollView {
            id: scroller
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentWidth: availableWidth
            clip: true

            Item {
                width: scroller.availableWidth
                implicitHeight: sectionLoader.implicitHeight + 36

                Loader {
                    id: sectionLoader
                    x: 36
                    // Right inset keeps the scrollbar clear of section cards.
                    width: parent.width - 36 - 28
                    sourceComponent: root.activeSection === "integrations"
                                     ? integrationsComponent
                                     : root.activeSection === "playback" ? playbackComponent
                                     : root.activeSection === "downloads" ? downloadsComponent
                                     : root.activeSection === "appearance" ? appearanceComponent
                                     : root.activeSection === "shortcuts" ? shortcutsComponent
                                     : root.activeSection === "about" ? aboutComponent
                                     : generalComponent
                }
            }
        }
    }

    Component {
        id: generalComponent
        GeneralSection {}
    }

    Component {
        id: downloadsComponent
        DownloadsSection {}
    }

    Component {
        id: appearanceComponent
        AppearanceSection {}
    }

    Component {
        id: integrationsComponent
        IntegrationsSection {}
    }

    Component {
        id: playbackComponent
        PlaybackSection {}
    }

    Component {
        id: shortcutsComponent
        ShortcutsSection {}
    }

    // Look mom, our very own credits screen!
    Component {
        id: aboutComponent
        AboutSection {}
    }
}
