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
import ".."

// Wraps one track row so it can be dragged to a new place in a local
// playlist, by its grip or with Alt+Up / Alt+Down. The hosting list needs
// "dragFrom" and "dropIndex" int properties for the drop marker.
Item {
    id: root

    required property Item view
    required property int index
    property bool reorderable: false
    property color accentColor: "#7fbe90"
    readonly property bool dragging: dragHandler.active
    default property alias content: holder.data

    // "to" is the index the song should end up at.
    signal moveRequested(int from, int to)

    // Where the dragged row's middle sits among the list's rows.
    function targetIndex() {
        const count = root.view.count;
        const middle = root.mapToItem(root.view.contentItem, 0, holder.y + holder.height / 2).y;
        const found = root.view.indexAt(root.view.width / 2, middle);
        if (found >= 0)
            return found;
        // Over the header or footer: stick to the nearest end.
        return middle < holder.height ? 0 : count - 1;
    }

    // Slide the list while a row is held near its top or bottom edge, like every other list that ever got long.
    function autoScroll() {
        const y = root.mapToItem(root.view, 0, holder.y + holder.height / 2).y;
        const top = root.view.originY;
        const bottom = root.view.originY + root.view.contentHeight - root.view.height;
        if (y < 70)
            root.view.contentY = Math.max(top, root.view.contentY - 14);
        else if (y > root.view.height - 90)
            root.view.contentY = Math.min(bottom, root.view.contentY + 14);
    }

    width: view.width
    height: holder.childrenRect.height
    z: dragging ? 10 : 0

    Keys.onPressed: function(event) {
        if (!root.reorderable || !(event.modifiers & Qt.AltModifier))
            return;
        if (event.key === Qt.Key_Up && root.index > 0)
            root.moveRequested(root.index, root.index - 1);
        else if (event.key === Qt.Key_Down && root.index < root.view.count - 1)
            root.moveRequested(root.index, root.index + 1);
        else
            return;
        event.accepted = true;
    }

    Item {
        id: holder

        width: parent.width
        height: root.height
        opacity: root.dragging ? 0.85 : 1
        scale: root.dragging ? 1.01 : 1

        Behavior on y {
            enabled: !root.dragging
            NumberAnimation { duration: Motion.normal; easing.type: Motion.enter }
        }

        // Grip: only drawn on hover, so the list stays quiet when nobody is rearranging.
        Item {
            id: grip

            visible: root.reorderable && (rowHover.hovered || root.dragging)
            x: 2
            width: 16
            height: parent.height
            z: 2

            LucideIcon {
                anchors.centerIn: parent
                width: 14
                height: 14
                name: "grip-vertical"
                color: root.dragging ? root.accentColor : "#8c928c"
            }

            DragHandler {
                id: dragHandler

                target: holder
                enabled: root.reorderable
                xAxis.enabled: false
                cursorShape: Qt.ClosedHandCursor
                onActiveChanged: {
                    if (active) {
                        root.view.dragFrom = root.index;
                        return;
                    }
                    const to = root.targetIndex();
                    root.view.dropIndex = -1;
                    root.view.dragFrom = -1;
                    holder.y = 0;
                    if (to !== root.index)
                        root.moveRequested(root.index, to);
                }
            }

            HoverHandler {
                cursorShape: root.reorderable ? Qt.OpenHandCursor : Qt.ArrowCursor
            }
        }
    }

    // Shows where the song will land.
    Rectangle {
        visible: root.reorderable && root.view.dropIndex === root.index && root.view.dragFrom !== root.index
        y: root.view.dragFrom > root.index ? 0 : root.height - height
        width: parent.width
        height: 2
        radius: 1
        color: root.accentColor
        z: 5
    }

    HoverHandler {
        id: rowHover
    }

    Connections {
        enabled: root.dragging
        target: holder

        function onYChanged() {
            root.view.dropIndex = root.targetIndex();
        }
    }

    Timer {
        interval: 16
        repeat: true
        running: root.dragging
        onTriggered: {
            root.autoScroll();
            root.view.dropIndex = root.targetIndex();
        }
    }
}
