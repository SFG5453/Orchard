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
import ".."

// Settings popup shell: glass backdrop and transitions. The panel lives in SettingsPanel.ui.qml.
Popup {
    id: root
    required property Item backdrop
    // Docs live in their own popup; the shell closes this one and opens it.
    signal docsRequested
    // Same deal for bug reports: the shell swaps this popup for the report one.
    signal supportRequested
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(980, parent.width - 64)
    height: Math.min(680, parent.height - 64)
    padding: 0
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    Overlay.modal: Rectangle {
        color: "#66000000"
    }

    enter: Transition {
        ParallelAnimation {
            NumberAnimation {
                property: "opacity"
                from: 0.0
                to: 1.0
                duration: 250
                easing.type: Easing.OutCubic
            }
            NumberAnimation {
                property: "scale"
                from: 0.95
                to: 1.0
                duration: 250
                easing.type: Easing.OutCubic
            }
        }
    }

    exit: Transition {
        ParallelAnimation {
            NumberAnimation {
                property: "opacity"
                from: 1.0
                to: 0.0
                duration: 200
                easing.type: Easing.InCubic
            }
            NumberAnimation {
                property: "scale"
                from: 1.0
                to: 0.95
                duration: 200
                easing.type: Easing.InCubic
            }
        }
    }

    background: Loader {
        active: root.visible
        sourceComponent: Item {
            Glass {
                anchors.fill: parent
                backdrop: root.backdrop
                // Track the backdrop so artwork and the immersive background keep moving behind the blur.
                live: true
                // Large panel: dimmer and flatter so the lens effect doesn't compete with the text.
                // Frosted glass, not a magnifying glass. Nobody needs to see their album art through a telescope.
                brightness: -0.35
                blurRadius: 36
                refraction: 0.3
                chromaticAberration: 0.0
                edgeHighlight: 0.15
                fresnel: 0.25
            }
            Rectangle {
                anchors.fill: parent
                radius: 20
                color: "#660e1210"
                border.color: "#1cffffff"
                border.width: 1
            }
        }
    }

    contentItem: SettingsPanel {
        id: panel

        docsLink.onClicked: root.docsRequested()
        supportLink.onClicked: root.supportRequested()
        closeButton.onClicked: root.close()

        navList.delegate: SettingsNavItem {
            required property var modelData
            required property int index
            // The Repeater parents delegates to the sidebar column; panel.width would span the whole popup.
            width: parent ? parent.width : 0
            entry: modelData
            current: panel.currentIndex === index
            onClicked: panel.currentIndex = index
        }

        // Cards animate themselves in; start each section from the top.
        sectionLoader.onLoaded: {
            panel.scroller.contentItem.contentY = 0;
            panel.headerFade.restart();
        }

        Connections {
            target: panel.sectionLoader.item
            ignoreUnknownSignals: true

            function onSignOutRequested() {
                root.close();
                OrchardAuth.signOut();
            }
        }
    }
}
