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
import ".."
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Orchard Connect device picker: play here, or drive another device on the same account.
// OrchardConnect is a context property from main.cpp.
// qmllint disable unqualified
GlassPopup {
    id: root

    width: Math.min(400, parent.width - 48)
    height: Math.min(content.implicitHeight + 40, parent.height - 64)

    // Untyped helpers: compiled bindings reading these maps trip qmlcachegen.
    function deviceIcon(device) {
        return device && device.kind === "mobile" ? "smartphone" : "monitor";
    }

    function sessionLabel(state, transport) {
        switch (state) {
        case "DISCOVERING":
        case "CONNECTING":
        case "AUTHENTICATING":
        case "NEGOTIATING_CAPABILITIES":
        case "RESOLVING_INITIAL_PLAYBACK":
            return qsTr("Connecting…");
        case "RECONNECTING":
            return qsTr("Reconnecting…");
        case "CONNECTED":
            return transport === "webrtc" ? qsTr("Connected over the internet")
                                          : qsTr("Connected on this network");
        }
        return "";
    }

    function deviceDetail(device, peerId, state, transport) {
        if (!device)
            return "";
        if (!device.compatible)
            return qsTr("Update Orchard on this device to use Connect");
        if (device.id === peerId && state)
            return root.sessionLabel(state, transport);
        const now = device.currently_playing;
        if (now && now.title)
            return (now.playing ? qsTr("Playing %1") : qsTr("Paused on %1")).arg(now.title);
        return qsTr("Idle");
    }

    function localDetail(controlling, controllers) {
        if (controllers && controllers.length > 0)
            return qsTr("Controlled by %1").arg(controllers.join(", "));
        return controlling ? qsTr("Switch back to this computer") : qsTr("Playing here");
    }

    onOpened: OrchardConnect.clearMessage()

    component DeviceRow: ItemDelegate {
        id: row

        property string glyph: "monitor"
        property string title: ""
        property string detail: ""
        property bool current: false
        property bool busy: false

        Layout.fillWidth: true
        implicitHeight: 56
        hoverEnabled: true
        Accessible.name: title + (detail ? ", " + detail : "")

        background: Rectangle {
            radius: 14
            color: row.current ? "#24ffffff" : row.hovered ? "#14ffffff" : "transparent"
            border.color: row.activeFocus ? "#f2f0e9" : "transparent"
        }

        contentItem: RowLayout {
            spacing: 12

            LucideIcon {
                Layout.preferredWidth: 20
                Layout.preferredHeight: 20
                name: row.glyph
                color: row.current ? "#f2f0e9" : "#b4b8b1"
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2

                Text {
                    Layout.fillWidth: true
                    text: row.title
                    color: "#f5f3ee"
                    font.family: "Inter"
                    font.pixelSize: 13
                    font.weight: row.current ? Font.DemiBold : Font.Medium
                    elide: Text.ElideRight
                }

                Text {
                    Layout.fillWidth: true
                    visible: text.length > 0
                    text: row.detail
                    color: "#b4b8b1"
                    font.family: "Inter"
                    font.pixelSize: 11
                    elide: Text.ElideRight
                }
            }

            BusyIndicator {
                Layout.preferredWidth: 18
                Layout.preferredHeight: 18
                visible: row.busy
                running: visible
            }
        }
    }

    contentItem: Flickable {
        clip: true
        contentHeight: content.implicitHeight
        boundsBehavior: Flickable.StopAtBounds

        ColumnLayout {
            id: content

            x: 20
            y: 20
            width: parent.width - 40
            spacing: 8

            Text {
                text: qsTr("Orchard Connect")
                color: "#f5f3ee"
                font.family: "Inter"
                font.pixelSize: 17
                font.weight: Font.DemiBold
            }

            Text {
                Layout.fillWidth: true
                Layout.bottomMargin: 6
                wrapMode: Text.WordWrap
                color: "#b4b8b1"
                font.family: "Inter"
                font.pixelSize: 12
                text: !OrchardConnect.available
                      ? qsTr("Sign in to your Orchard account in Settings to play on your other devices.")
                      : !OrchardConnect.online
                        ? qsTr("Reaching Orchard Connect…")
                        : qsTr("Choose where music plays. Devices signed in to your Orchard account show up here.")
            }

            Text {
                Layout.fillWidth: true
                visible: OrchardConnect.message.length > 0
                wrapMode: Text.WordWrap
                color: "#f0b27a"
                font.family: "Inter"
                font.pixelSize: 12
                text: OrchardConnect.message
            }

            DeviceRow {
                glyph: "laptop"
                title: qsTr("This computer")
                detail: root.localDetail(OrchardConnect.controlling, OrchardConnect.controllers)
                current: !OrchardConnect.controlling && OrchardConnect.role !== "controller"
                onClicked: OrchardConnect.connectTo("")
            }

            Repeater {
                model: OrchardConnect.devices

                DeviceRow {
                    required property var modelData

                    readonly property bool mine: modelData.id === OrchardConnect.peer.id
                                                 && OrchardConnect.role === "controller"

                    glyph: root.deviceIcon(modelData)
                    title: modelData.name
                    detail: root.deviceDetail(modelData, OrchardConnect.peer.id, OrchardConnect.state,
                                              OrchardConnect.transport)
                    current: mine && OrchardConnect.state === "CONNECTED"
                    busy: mine && OrchardConnect.state !== "CONNECTED" && OrchardConnect.state !== ""
                    enabled: modelData.compatible
                    onClicked: OrchardConnect.connectTo(modelData.id)
                }
            }

            Text {
                Layout.fillWidth: true
                Layout.topMargin: 4
                visible: OrchardConnect.online && OrchardConnect.devices.length === 0
                wrapMode: Text.WordWrap
                color: "#8d928b"
                font.family: "Inter"
                font.pixelSize: 11
                text: qsTr("No other devices are online. Open Orchard on your phone and sign in to the same Orchard account.")
            }
        }
    }
}
