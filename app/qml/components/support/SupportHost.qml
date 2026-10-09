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
import QtQuick.Window

// Owns the report popup and the capture bar. The popup snapshots the window
// before it appears, and capture mode hides it until you have found the bug.
Item {
    id: root

    required property Item backdrop
    // Current shell page, recorded in diagnostics.
    property string page: ""
    property bool capturing: false
    property string pendingReport: ""

    readonly property bool popupOpen: loader.item !== null && loader.item.visible

    signal notice(string message)

    // waitForOverlays: another popup is closing; let it fade out of the snapshot first.
    function open(reportId, waitForOverlays) {
        if (root.capturing || root.popupOpen)
            return;
        root.pendingReport = reportId || "";
        openTimer.interval = waitForOverlays ? 240 : 0;
        openTimer.restart();
    }

    function show() {
        loader.active = true;
        Qt.callLater(() => {
            if (loader.item)
                loader.item.open();
        });
    }

    anchors.fill: parent

    Timer {
        id: openTimer
        onTriggered: {
            OrchardSupport.takeSnapshot(root.Window.window, root.page);
            root.show();
        }
    }

    Connections {
        target: OrchardSupport
        function onNotice(message) {
            root.notice(message);
        }
    }

    Loader {
        id: loader
        active: false

        sourceComponent: Component {
            SupportPopup {
                backdrop: root.backdrop
                reportId: root.pendingReport
                onClosed: {
                    if (!root.capturing)
                        loader.active = false;
                }
                onCaptureRequested: {
                    root.capturing = true;
                    close();
                }
            }
        }
    }

    SupportCaptureBar {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: parent.top
        anchors.topMargin: 28
        z: 200
        active: root.capturing && !root.popupOpen
        onCapture: {
            // Hidden first; the grab renders a fresh frame without the bar.
            root.capturing = false;
            OrchardSupport.captureWindow(root.Window.window, root.page);
            root.pendingReport = "";
            root.show();
        }
        onCancel: {
            root.capturing = false;
            root.pendingReport = "";
            root.show();
        }
    }
}
