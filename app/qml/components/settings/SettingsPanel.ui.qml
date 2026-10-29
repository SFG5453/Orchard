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
import Orchard
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ".."

// Sidebar, header and scrolling page of the settings window.
RowLayout {
    id: root

    // Nav entries; key doubles as the section id used by the loader switch. keywords feed "Find a setting".
    readonly property var sections: [
        { key: "general", label: qsTr("General"), icon: "sliders-horizontal", blurb: qsTr("Startup behavior and your account."), keywords: qsTr("startup tray window account sign in restore session updates") },
        { key: "playback", label: qsTr("Playback"), icon: "audio-lines", blurb: qsTr("Quality, audio engine, and transitions between songs."), keywords: qsTr("quality crossfade blend gapless autoplay queue volume equalizer ai skip") },
        { key: "downloads", label: qsTr("Downloads"), icon: "download", blurb: qsTr("Offline listening, download quality, storage, and lyric translation models."), keywords: qsTr("offline storage translation models lyrics") },
        { key: "appearance", label: qsTr("Appearance"), icon: "palette", blurb: qsTr("Colors, layout, artwork, and backgrounds."), keywords: qsTr("theme color accent popup menu background layout artwork scale size") },
        { key: "integrations", label: qsTr("Integrations"), icon: "plug", blurb: qsTr("Music services and the apps Orchard talks to."), keywords: qsTr("apple music spotify youtube last.fm discord connect integrations") },
        { key: "shortcuts", label: qsTr("Shortcuts"), icon: "keyboard", blurb: qsTr("Keyboard shortcuts for playback and navigation."), keywords: qsTr("keyboard keys hotkeys") },
        { key: "about", label: qsTr("About"), icon: "info", blurb: qsTr("Version, release notes, credits, and open source licenses."), keywords: qsTr("version licenses release notes credits") }
    ]
    property int currentIndex: 0
    // Entries left after "Find a setting"; the logic layer keeps it current.
    property int matchCount: sections.length
    readonly property var currentSection: sections[currentIndex]
    readonly property string activeSection: currentSection.key
    property alias navList: navList
    property alias searchField: searchField
    property alias profileButton: profileButton
    property alias docsLink: docsLink
    property alias supportLink: supportLink
    property alias closeButton: closeButton
    property alias headerFade: headerFade
    property alias headerSlide: headerSlide
    property alias scroller: scroller
    property alias sectionLoader: sectionLoader

    spacing: 0

    ColumnLayout {
        Layout.preferredWidth: 272
        Layout.fillHeight: true
        Layout.margins: 16
        Layout.topMargin: 20
        spacing: 4

        ItemDelegate {
            id: profileButton
            Layout.fillWidth: true
            implicitHeight: 76
            leftPadding: 8
            hoverEnabled: true
            Accessible.name: qsTr("Orchard Account")
            // Falls back to the YouTube Music profile when no Orchard account is signed in.
            readonly property bool useYoutube: !OrchardAccount.isSignedIn && OrchardAuth.isSignedIn
            readonly property string profileName: OrchardAccount.isSignedIn ? OrchardAccount.userName
                : useYoutube ? (OrchardAuth.userName || "") : ""
            readonly property string profilePicture: OrchardAccount.isSignedIn ? OrchardAccount.userPicture
                : useYoutube ? (OrchardAuth.userAvatar || "") : ""

            contentItem: RowLayout {
                spacing: 14

                Rectangle {
                    Layout.preferredWidth: 56
                    Layout.preferredHeight: 56
                    radius: 28
                    color: Qt.tint("#1d211e", Qt.alpha(OrchardAppearance.accent, 0.45))
                    clip: true

                    Text {
                        anchors.centerIn: parent
                        visible: avatar.status !== Image.Ready
                        text: (profileButton.profileName || "O").charAt(0).toUpperCase()
                        color: "#ffffff"
                        font.family: "Inter"
                        font.pixelSize: 22
                        font.weight: Font.DemiBold
                    }

                    Image {
                        id: avatar
                        anchors.fill: parent
                        source: profileButton.profilePicture
                        fillMode: Image.PreserveAspectCrop
                        sourceSize: Qt.size(112, 112)
                        visible: status === Image.Ready
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2

                    Text {
                        Layout.fillWidth: true
                        text: profileButton.profileName || qsTr("Orchard")
                        color: "#ffffff"
                        font.family: "Inter"
                        font.pixelSize: 15
                        font.weight: Font.DemiBold
                        elide: Text.ElideRight
                    }

                    Text {
                        Layout.fillWidth: true
                        text: OrchardAccount.isSignedIn ? qsTr("Orchard Account")
            : profileButton.useYoutube ? qsTr("YouTube Music") : qsTr("Not signed in")
                        color: "#b9bfba"
                        font.family: "Inter"
                        font.pixelSize: 12
                        elide: Text.ElideRight
                    }
                }
            }

            background: Rectangle {
                radius: 8
                color: profileButton.down ? "#0cffffff" : profileButton.hovered ? "#0fffffff" : "transparent"
                border.color: profileButton.visualFocus ? OrchardAppearance.accentStrong : "transparent"
                Behavior on color { ColorAnimation { duration: Motion.fast } }
            }
        }

        TextField {
            id: searchField
            Layout.fillWidth: true
            Layout.topMargin: 8
            Layout.bottomMargin: 10
            implicitHeight: 34
            leftPadding: 12
            rightPadding: 36
            placeholderText: qsTr("Find a setting")
            placeholderTextColor: "#9aa19c"
            color: "#f0eee7"
            selectionColor: Qt.alpha(OrchardAppearance.accent, 0.45)
            font.family: "Inter"
            font.pixelSize: 14
            Accessible.name: qsTr("Find a setting")
            // Escape clears the search first; the next one closes the window.
            Keys.onEscapePressed: event => {
                event.accepted = text.length > 0;
                text = "";
            }

            LucideIcon {
                anchors.right: parent.right
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                width: 15
                height: 15
                name: searchField.text.length > 0 ? "x" : "search"
                color: "#c3c8c4"
            }

            background: Rectangle {
                radius: 5
                color: searchField.activeFocus ? "#1a1d1b" : searchField.hovered ? "#16ffffff" : "#0fffffff"
                border.color: "#14ffffff"
                Behavior on color { ColorAnimation { duration: Motion.fast } }

                // Focus underline grows out from the middle.
                Rectangle {
                    anchors.bottom: parent.bottom
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: searchField.activeFocus ? parent.width - 2 : 0
                    height: 2
                    radius: 1
                    color: OrchardAppearance.accent
                    Behavior on width { NumberAnimation { duration: Motion.slow; easing.type: Easing.OutQuint } }
                }
            }
        }

        Item {
            id: navArea
            Layout.fillWidth: true
            implicitHeight: navColumn.implicitHeight

            readonly property Item currentItem: navList.count > 0 && navList.itemAt(root.currentIndex) && navList.itemAt(root.currentIndex).visible
                                                ? navList.itemAt(root.currentIndex) : null

            Rectangle {
                visible: navArea.currentItem !== null
                width: parent.width
                height: 38
                radius: 6
                color: "#14ffffff"
                y: navArea.currentItem ? navArea.currentItem.y : 0
                Behavior on y { NumberAnimation { duration: 320; easing.type: Easing.OutQuint } }
            }

            // Accent bar: the leading edge moves first and the trailing edge catches up, so it stretches in flight.
            Rectangle {
                id: bar
                readonly property real targetTop: navArea.currentItem ? navArea.currentItem.y + 11 : 0
                property real edgeTop: 0
                property real edgeBottom: 16
                property bool movingDown: true
                visible: navArea.currentItem !== null
                x: 0
                y: edgeTop
                width: 3
                height: Math.max(4, edgeBottom - edgeTop)
                radius: 1.5
                color: OrchardAppearance.accent
                // Direction first, so each edge's Behavior picks its duration before it starts.
                onTargetTopChanged: {
                    movingDown = targetTop >= edgeTop;
                    edgeTop = targetTop;
                    edgeBottom = targetTop + 16;
                }
                Behavior on edgeTop { NumberAnimation { duration: bar.movingDown ? 380 : 190; easing.type: Easing.OutCubic } }
                Behavior on edgeBottom { NumberAnimation { duration: bar.movingDown ? 190 : 380; easing.type: Easing.OutCubic } }
            }

            Column {
                id: navColumn
                width: parent.width
                spacing: 3

                // The logic layer swaps in a delegate that selects and filters entries.
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

        Text {
            Layout.fillWidth: true
            Layout.leftMargin: 14
            Layout.topMargin: 8
            visible: root.matchCount === 0
            text: qsTr("No settings match “%1”").arg(searchField.text)
            color: "#b9bfba"
            font.family: "Inter"
            font.pixelSize: 13
            wrapMode: Text.WordWrap
        }

        Item {
            Layout.fillHeight: true
        }

        // Launchers, not sections: they open their own popups.
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
            badge: OrchardSupport.unread > 0
        }
    }

    ColumnLayout {
        Layout.fillWidth: true
        Layout.fillHeight: true
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 22
            Layout.leftMargin: 28
            Layout.rightMargin: 16
            Layout.bottomMargin: 18
            spacing: 16

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4
                transform: Translate { id: headerSlide; x: 0 }

                NumberAnimation on opacity {
                    id: headerFade
                    from: 0
                    to: 1
                    duration: 260
                    easing.type: Easing.OutCubic
                }

                Text {
                    Layout.fillWidth: true
                    text: root.currentSection.label
                    color: "#ffffff"
                    font.family: "Inter"
                    font.pixelSize: 28
                    font.weight: Font.DemiBold
                }

                Text {
                    Layout.fillWidth: true
                    text: root.currentSection.blurb
                    color: "#b9bfba"
                    font.family: "Inter"
                    font.pixelSize: 13
                    wrapMode: Text.WordWrap
                }
            }

            ToolButton {
                id: closeButton
                Layout.alignment: Qt.AlignTop
                implicitWidth: 40
                implicitHeight: 32
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
                    radius: 5
                    color: closeButton.down ? "#a8261a" : closeButton.hovered ? "#c42b1c" : "transparent"
                    border.color: closeButton.visualFocus ? OrchardAppearance.accentStrong : "transparent"
                    Behavior on color { ColorAnimation { duration: Motion.fast } }
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
                    x: 28
                    // Right inset keeps the scrollbar clear of section cards.
                    width: Math.min(parent.width - 28 - 28, 1000)
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

            FlickableWheel {
                view: scroller.contentItem
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

    Component {
        id: aboutComponent
        AboutSection {}
    }
}
