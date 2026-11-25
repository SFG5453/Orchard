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
import "../docs"

// One release: header, a panel per section, and links to the neighbouring releases.
ColumnLayout {
    id: root

    // Entry built by ReleasesView, or null when nothing matches.
    property var entry: null
    // Neighbours in the unfiltered history.
    property var newer: null
    property var older: null
    property bool copied: false

    signal releaseRequested(string version)
    signal copyRequested
    signal closeRequested

    // The scroll area's Flickable, untyped because Qt exposes it as a plain item.
    readonly property var flickable: scroller.contentItem

    onEntryChanged: {
        root.flickable.contentY = 0;
        headerFade.restart();
    }

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
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Text {
                    text: root.entry ? root.entry.version : qsTr("No release notes")
                    elide: Text.ElideRight
                    color: "#f0eee7"
                    font.family: "Inter"
                    font.pixelSize: 26
                    font.weight: Font.DemiBold
                }
                ReleaseChip {
                    visible: root.entry !== null && root.entry.isNew
                    text: qsTr("New")
                    tone: "#e8c47c"
                }
                ReleaseChip {
                    visible: root.entry !== null && root.entry.installed
                    text: qsTr("Installed")
                }
                ReleaseChip {
                    visible: root.entry !== null && root.entry.channel !== ""
                    text: root.entry ? root.entry.channel : ""
                    tone: "#9ab8d8"
                }
                Item { Layout.fillWidth: true }
            }
            Text {
                Layout.fillWidth: true
                text: root.entry ? root.entry.title : qsTr("This build has no release notes.")
                wrapMode: Text.WordWrap
                color: "#c9ccc4"
                font.family: "Inter"
                font.pixelSize: 15
            }
            Text {
                Layout.fillWidth: true
                visible: root.entry !== null
                text: root.entry ? root.entry.meta : ""
                color: "#8d968e"
                font.family: "Inter"
                font.pixelSize: 12
            }
        }

        DocsPillButton {
            Layout.alignment: Qt.AlignTop
            iconName: "copy"
            done: root.copied
            text: root.copied ? qsTr("Copied") : qsTr("Copy notes")
            enabled: root.entry !== null
            Accessible.name: qsTr("Copy these release notes as Markdown")
            onClicked: root.copyRequested()
        }

        RoundButton {
            id: closeButton
            Layout.alignment: Qt.AlignTop
            implicitWidth: 34
            implicitHeight: 34
            hoverEnabled: true
            Accessible.name: qsTr("Close release notes")
            onClicked: root.closeRequested()
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
                border.color: closeButton.visualFocus ? OrchardAppearance.accentStrong : "transparent"
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
            implicitHeight: column.implicitHeight + 36

            ColumnLayout {
                id: column
                x: 36
                width: Math.min(760, parent.width - 36 - 28)
                spacing: 14

                ReleaseCallout {
                    Layout.fillWidth: true
                    title: root.entry ? root.entry.callout.title : ""
                    body: root.entry ? root.entry.callout.text : ""
                    action: root.entry ? root.entry.callout.action : ""
                    url: root.entry ? root.entry.callout.url : ""
                }

                Repeater {
                    model: root.entry ? root.entry.sections : []

                    delegate: Rectangle {
                        id: panel
                        required property var modelData
                        required property int index
                        readonly property color tone: panel.modelData.tone

                        Layout.fillWidth: true
                        implicitHeight: body.implicitHeight + 40
                        radius: 16
                        color: "#0affffff"
                        border.color: "#1cffffff"
                        opacity: 0
                        transform: Translate { id: slide; y: 18 }

                        Component.onCompleted: entrance.start()

                        SequentialAnimation {
                            id: entrance
                            PauseAnimation { duration: Math.min(panel.index, 6) * 45 }
                            ParallelAnimation {
                                NumberAnimation { target: panel; property: "opacity"; to: 1; duration: 260; easing.type: Easing.OutCubic }
                                NumberAnimation { target: slide; property: "y"; to: 0; duration: 340; easing.type: Easing.OutCubic }
                            }
                        }

                        ColumnLayout {
                            id: body
                            x: 20
                            y: 20
                            width: panel.width - 40
                            spacing: 12

                            RowLayout {
                                spacing: 10
                                Rectangle {
                                    Layout.preferredWidth: 30
                                    Layout.preferredHeight: 30
                                    radius: 15
                                    color: Qt.rgba(panel.tone.r, panel.tone.g, panel.tone.b, 0.16)
                                    LucideIcon {
                                        anchors.centerIn: parent
                                        width: 16
                                        height: 16
                                        name: panel.modelData.icon
                                        color: panel.tone
                                    }
                                }
                                Text {
                                    text: panel.modelData.title
                                    color: "#f0eee7"
                                    font.family: "Inter"
                                    font.pixelSize: 16
                                    font.weight: Font.DemiBold
                                }
                                Text {
                                    text: panel.modelData.items.length
                                    color: "#8d968e"
                                    font.family: "Inter"
                                    font.pixelSize: 12
                                }
                            }

                            Repeater {
                                model: panel.modelData.items

                                delegate: RowLayout {
                                    id: item
                                    required property string modelData
                                    Layout.fillWidth: true
                                    spacing: 12

                                    Rectangle {
                                        Layout.alignment: Qt.AlignTop
                                        Layout.topMargin: 8
                                        Layout.preferredWidth: 5
                                        Layout.preferredHeight: 5
                                        radius: 3
                                        color: panel.tone
                                        opacity: 0.8
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: item.modelData
                                        wrapMode: Text.WordWrap
                                        color: "#d6d9d3"
                                        font.family: "Inter"
                                        font.pixelSize: 14
                                        lineHeight: 1.25
                                    }
                                }
                            }
                        }
                    }
                }

                RowLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 6
                    visible: root.entry !== null
                    spacing: 12

                    NeighbourButton {
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        release: root.newer
                        caption: qsTr("Newer")
                        iconName: "chevron-left"
                        onClicked: root.releaseRequested(root.newer.version)
                    }
                    NeighbourButton {
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        release: root.older
                        caption: qsTr("Older")
                        iconName: "chevron-right"
                        trailingIcon: true
                        onClicked: root.releaseRequested(root.older.version)
                    }
                }
            }
        }

        FlickableWheel {
            view: scroller.contentItem
        }
    }

    // Card pointing at the neighbouring release; empty at either end of the history.
    component NeighbourButton: Button {
        id: nav
        property var release: null
        property string caption
        property string iconName
        property bool trailingIcon: false

        implicitHeight: 56
        enabled: nav.release !== null
        opacity: nav.release !== null ? 1 : 0.35
        hoverEnabled: true
        leftPadding: 16
        rightPadding: 16
        Accessible.name: nav.release ? qsTr("%1 release, %2").arg(nav.caption).arg(nav.release.version) : nav.caption

        contentItem: RowLayout {
            spacing: 12
            layoutDirection: nav.trailingIcon ? Qt.RightToLeft : Qt.LeftToRight
            LucideIcon {
                Layout.preferredWidth: 16
                Layout.preferredHeight: 16
                name: nav.iconName
                color: "#a4aaa1"
            }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    Layout.fillWidth: true
                    text: nav.caption
                    horizontalAlignment: nav.trailingIcon ? Text.AlignRight : Text.AlignLeft
                    color: "#8d968e"
                    font.family: "Inter"
                    font.pixelSize: 11
                    font.weight: Font.DemiBold
                    font.capitalization: Font.AllUppercase
                    font.letterSpacing: 0.6
                }
                Text {
                    Layout.fillWidth: true
                    text: nav.release ? nav.release.version : "-"
                    horizontalAlignment: nav.trailingIcon ? Text.AlignRight : Text.AlignLeft
                    elide: Text.ElideRight
                    color: "#d6d9d3"
                    font.family: "Inter"
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                }
            }
        }
        background: Rectangle {
            radius: 12
            color: nav.down ? "#22ffffff" : nav.hovered && nav.enabled ? "#16ffffff" : "#0affffff"
            border.color: nav.visualFocus ? OrchardAppearance.accentStrong : "#1cffffff"
            Behavior on color { ColorAnimation { duration: 120 } }
        }
    }
}
