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
import QtQuick.Window
import Orchard
import "components"
import "components/local"
import "components/player"

Window {
    id: mainWindow

    property bool troubleModalOpen: false
    // Height the floating player overlaps at the bottom of scrolling pages.
    property real playerInset: 0

    function toggleMaximized() {
        if (mainWindow.visibility === Window.Maximized)
            mainWindow.showNormal();
        else
            mainWindow.showMaximized();
    }

    visible: true
    width: OrchardWindowState.initialWidth
    height: OrchardWindowState.initialHeight
    minimumWidth: 900
    minimumHeight: 600
    title: qsTr("Orchard")
    Component.onCompleted: {
        if (OrchardWindowState.initialMaximized)
            mainWindow.showMaximized();
    }
    color: "#0d0f12"
    flags: Qt.Window | Qt.FramelessWindowHint
    onClosing: close => {
        // Closing to the tray keeps the music going; the tray menu holds the real exit.
        if (OrchardTray.available && OrchardTray.closeToTray && OrchardAuth.isSignedIn) {
            close.accepted = false;
            mainWindow.hide();
            return;
        }
        Qt.quit();
    }

    ShaderEffect {
        id: meshBackground
        anchors.fill: parent
        z: -100
        visible: !(homeShell.visible && homeShell.item && homeShell.item.immersiveReady)

        property real time: 0.0
        property vector2d extent: Qt.vector2d(width, height)

        NumberAnimation on time {
            running: meshBackground.visible && mainWindow.visibility !== Window.Minimized && mainWindow.visible
            loops: Animation.Infinite
            from: 0.0
            to: 1000.0
            duration: 1000000
        }

        fragmentShader: "qrc:/shaders/mesh.frag.qsb"
    }

    Item {
        id: titleBar

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 38
        // Stack below contentArea itself so its controls receive presses first.
        z: -1

        DragHandler {
            id: windowDragHandler

            target: null
            // Never steal a press already owned by a button or another control.
            grabPermissions: PointerHandler.ApprovesTakeOverByAnything
            onActiveChanged: {
                if (active)
                    mainWindow.startSystemMove();
            }
        }

        TapHandler {
            acceptedButtons: Qt.LeftButton
            gesturePolicy: TapHandler.DragThreshold
            // Two taps: the traditional shortcut for making the orchard roomy.
            onDoubleTapped: mainWindow.toggleMaximized()
        }
    }

    WindowControls {
        id: windowControls

        anchors.right: parent.right
        anchors.top: parent.top
        targetWindow: mainWindow
        z: 10
        autoHide: mainWindow.visibility === Window.FullScreen && homeShell.item !== null && homeShell.item.fullscreenOpen
    }

    Item {
        id: contentArea

        anchors.fill: parent

        // Passive, so it sees pointer motion even over controls that take hover themselves.
        // Animated items resend hover under a still cursor, so only real movement counts.
        HoverHandler {
            property point last: Qt.point(-1, -1)
            onPointChanged: {
                const pos = point.scenePosition;
                if (Math.abs(pos.x - last.x) < 1 && Math.abs(pos.y - last.y) < 1)
                    return;
                last = pos;
                if (windowControls.autoHide)
                    windowControls.wake();
            }
        }

        LoginView {
            id: loginView

            visible: !OrchardAuth.isSignedIn && !OrchardAuth.authWebViewActive && !OrchardAuth.profileLoading
            onLoginRequested: OrchardAuth.startLogin()
            onTroubleRequested: mainWindow.troubleModalOpen = true
        }

        Loader {
            id: homeShell

            anchors.fill: parent
            visible: active && OrchardAuth.isSignedIn && OrchardAuth.status !== "starting"
            sourceComponent: Component { HomeShell {} }
        }

        Column {
            anchors.centerIn: parent
            spacing: 12
            visible: OrchardAuth.profileLoading && !OrchardAuth.authWebViewActive

            BusyIndicator {
                anchors.horizontalCenter: parent.horizontalCenter
                running: parent.visible
            }

            Text {
                text: qsTr("Loading your YouTube profile…")
                color: "#9299a3"
                font.pixelSize: 13
                font.family: "Inter"
            }
        }

        Loader {
            anchors.fill: parent
            active: OrchardMusicVideo.open && homeShell.visible
            sourceComponent: Component { MusicVideoTheater {} }
        }
    }

    // Owns the file dialogs behind "Add local files", covers and lyrics.
    LocalPickerHost {}

    // Above everything, including window controls, so edges stay grabbable.
    WindowResizeHandles {
        targetWindow: mainWindow
        z: 100
    }
}
