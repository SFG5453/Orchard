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

// Modal panel on frosted glass. Subclasses set the size and the content item.
Popup {
    id: root
    required property Item backdrop

    parent: Overlay.overlay
    anchors.centerIn: parent
    padding: 0
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    Overlay.modal: Rectangle {
        color: "#66000000"
    }

    enter: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 250; easing.type: Easing.OutCubic }
            NumberAnimation { property: "scale"; from: 0.95; to: 1.0; duration: 250; easing.type: Easing.OutCubic }
        }
    }

    exit: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 200; easing.type: Easing.InCubic }
            NumberAnimation { property: "scale"; from: 1.0; to: 0.95; duration: 200; easing.type: Easing.InCubic }
        }
    }

    background: Loader {
        active: root.visible
        sourceComponent: Item {
            Glass {
                anchors.fill: parent
                backdrop: root.backdrop
                // Track the backdrop so artwork keeps moving behind the blur.
                live: true
                // Reading surface: dim and flat so the lens never competes with the text.
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
}
