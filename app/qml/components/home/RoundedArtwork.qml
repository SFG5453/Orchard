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
import QtQuick.Window

Item {
    id: root

    property url source: ""
    property real radius: 10
    // Square bottom corners let a card footer sit flush under the cover.
    property real bottomRadius: radius
    // Stepped so a resizing cover refetches a few times instead of every frame.
    readonly property int artworkPixels: Math.max(64, Math.ceil(Math.max(width, height) * Screen.devicePixelRatio / 32) * 32)
    property url shownSource: ""

    function sizedSource(value) {
        const url = value.toString();
        // Apple thumbnails take any size in the path; query-string URLs are signed.
        const apple = /^(https:\/\/[^/]*mzstatic\.com\/image\/thumb\/.*\/)(\d+)x(\d+)([a-z]{2}\.(?:jpg|png|webp))$/i.exec(url);
        if (apple) {
            const height = Math.round(artworkPixels * Number(apple[3]) / Math.max(1, Number(apple[2])));
            return apple[1] + artworkPixels + "x" + height + apple[4];
        }
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
        // Sampled by the mask shader; a hidden Image still provides its texture.
        visible: false
    }

    // Rounds corners in one pass so cards need no offscreen layers.
    ShaderEffect {
        readonly property real sourceAspect: image.implicitHeight > 0 ? image.implicitWidth / image.implicitHeight : 1
        readonly property real itemAspect: height > 0 ? width / height : 1
        property var source: image
        property size size: Qt.size(width, height)
        property size cropScale: sourceAspect > itemAspect ? Qt.size(itemAspect / sourceAspect, 1)
                                                           : Qt.size(1, sourceAspect / itemAspect)
        property real radius: root.radius
        property real bottomRadius: root.bottomRadius

        anchors.fill: parent
        opacity: image.opacity
        visible: image.status === Image.Ready || image.opacity > 0
        fragmentShader: "qrc:/shaders/rounded_artwork.frag.qsb"
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
}
