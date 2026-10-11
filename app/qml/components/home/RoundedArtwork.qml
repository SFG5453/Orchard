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

import Orchard
import QtQuick
import QtQuick.Effects
import QtQuick.Window

Item {
    id: root

    property url source: ""
    property real radius: 10
    // Square bottom corners let a card footer sit flush under the cover.
    property real bottomRadius: radius
    // Stepped so a resizing cover refetches a few times instead of every frame.
    readonly property int artworkPixels: Math.max(128, Math.ceil(Math.max(width, height) * Screen.devicePixelRatio * 1.15 / 128) * 128)
    property url shownSource: ""

    function sizedSource(value) {
        const url = value.toString();
        // More pixels, fewer witness-protection portraits. Only resize Google's
        // image CDN URLs; other providers may use signed image addresses.
        if (!/^https:\/\/[^/]*(?:googleusercontent\.com|ggpht\.com)\//i.test(url))
            return url;
        return url.replace(/([=-])s\d+(?=[-/?#]|$)/i, "$1s" + artworkPixels)
                  .replace(/([=-])w\d+(?=[-/?#]|$)/i, "$1w" + artworkPixels)
                  .replace(/-h\d+(?=[-/?#]|$)/i, "-h" + artworkPixels);
    }

    Rectangle {
        anchors.fill: parent
        radius: root.radius
        bottomLeftRadius: root.bottomRadius
        bottomRightRadius: root.bottomRadius
        color: "#252a2c"
    }

    Image {
        id: image

        anchors.fill: parent
        source: root.sizedSource(root.source)
        asynchronous: true
        fillMode: Image.PreserveAspectCrop
        sourceSize.width: root.artworkPixels
        // A resize reload keeps the old pixels up; only a new cover fades over the placeholder.
        retainWhileLoading: true
        opacity: 0
        onStatusChanged: {
            if (status === Image.Ready) {
                if (opacity < 1 && !fadeIn.running)
                    fadeIn.restart();
                root.shownSource = root.source;
            } else if (root.source.toString() !== root.shownSource.toString()) {
                opacity = 0;
            }
        }
        Component.onCompleted: {
            if (status === Image.Ready) {
                opacity = 1;
                root.shownSource = root.source;
            }
        }
        layer.enabled: true

        layer.effect: MultiEffect {
            maskEnabled: true
            maskSource: mask
        }
    }

    NumberAnimation {
        id: fadeIn

        target: image
        property: "opacity"
        from: 0
        to: 1
        duration: Motion.normal
        easing.type: Motion.enter
    }

    Rectangle {
        id: mask

        anchors.fill: parent
        radius: root.radius
        bottomLeftRadius: root.bottomRadius
        bottomRightRadius: root.bottomRadius
        visible: false
        layer.enabled: true
    }
}
