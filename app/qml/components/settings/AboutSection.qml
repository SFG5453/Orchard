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

// Credits and bundled licenses. Layout lives in AboutSectionForm.ui.qml.
AboutSectionForm {
    id: root
    appVersion: Qt.application.version
    releaseNotes: OrchardReleaseNotes

    function showLicenseModal(title, key) {
        licenseModal.licenseTitle = title;
        licenseModal.licenseText = OrchardBackend.licenseText(key);
        licenseModal.open();
    }

    orchardLinkButton.onClicked: Qt.openUrlExternally("https://sfg545.dev/orchard")
    orchardLicenseButton.onClicked: root.showLicenseModal(qsTr("Orchard License (AGPL-3.0)"), "orchard")

    licenseList.delegate: SettingsLicenseCard {
        required property var modelData
        name: modelData.name
        license: modelData.license
        blurb: modelData.description
        linkButton.onClicked: Qt.openUrlExternally(modelData.url)
        licenseButton.onClicked: root.showLicenseModal(modelData.name + " (" + modelData.license + ")", modelData.key)
    }

    // Modal License Viewer
    Popup {
        id: licenseModal

        property string licenseTitle: ""
        property string licenseText: ""

        parent: Overlay.overlay
        anchors.centerIn: parent
        width: Math.min(760, parent.width - 48)
        height: Math.min(540, parent.height - 64)
        padding: 20
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        Overlay.modal: Rectangle {
            color: "#80000000"
        }

        enter: Transition {
            NumberAnimation {
                property: "opacity"
                from: 0.0
                to: 1.0
                duration: 180
                easing.type: Easing.OutCubic
            }
        }

        exit: Transition {
            NumberAnimation {
                property: "opacity"
                from: 1.0
                to: 0.0
                duration: 140
                easing.type: Easing.InCubic
            }
        }

        background: Rectangle {
            color: "#181b1e"
            radius: 14
            border.color: "#35ffffff"
            border.width: 1
        }

        contentItem: LicenseViewer {
            licenseTitle: licenseModal.licenseTitle
            licenseText: licenseModal.licenseText
            copied: copyTimer.running
            closeButton.onClicked: licenseModal.close()
            copyButton.onClicked: {
                textArea.selectAll();
                textArea.copy();
                textArea.deselect();
                copyTimer.restart();
            }

            Timer {
                id: copyTimer
                interval: 1800
            }
        }
    }
}
