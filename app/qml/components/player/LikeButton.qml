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

import ".."
import QtQuick
import QtQuick.Controls
import Orchard

// Likes the playing song on YouTube Music.
Button {
    id: root

    property color iconColor: "#d8dcd6"
    property color likedColor: "#8cc5a5"
    property int iconSize: 17
    readonly property bool liked: OrchardLibrary.currentLiked

    implicitWidth: 32
    implicitHeight: 32
    hoverEnabled: true
    focusPolicy: Qt.TabFocus
    enabled: Boolean(OrchardPlayback.track.id) && OrchardAuth.isSignedIn && !OrchardLibrary.likeBusy
    onClicked: OrchardLibrary.toggleCurrentLike()
    Accessible.name: ToolTip.text
    Accessible.checkable: true
    Accessible.checked: liked
    ToolTip.visible: hovered
    ToolTip.delay: 500
    ToolTip.text: liked ? qsTr("Remove from liked songs") : qsTr("Like")

    background: Rectangle {
        radius: width / 2
        border.color: root.activeFocus ? root.iconColor : "transparent"
        color: root.hovered && root.enabled ? "#1fffffff" : "transparent"
        Behavior on color { ColorAnimation { duration: 90 } }
    }

    contentItem: LucideIcon {
        name: root.liked ? "heart-filled" : "heart"
        color: root.liked ? root.likedColor : root.iconColor
        // Hollow at half strength until YouTube says where this song stands.
        opacity: !OrchardAuth.isSignedIn || !OrchardPlayback.track.id ? 0.4 : OrchardLibrary.likeKnown ? 1 : 0.6
        implicitWidth: root.iconSize
        implicitHeight: root.iconSize
        scale: root.down ? 0.85 : 1
        Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutBack } }
    }
}
