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

// Momentum scrolling for wheel input: notches add velocity, friction bleeds it off,
// and a spring pulls overshoot at either end back to the edge.
// A notch is a polite shove; friction is the landlord who ends the party.
FrameAnimation {
    id: root

    required property Flickable view
    property bool horizontal: false
    // Seconds for friction to shrink the velocity by a factor of e. A notch travels delta pixels in total.
    property real friction: 0.3
    // Spring stiffness in 1/s for the edge bounce.
    property real omega: 14
    property real maxVelocity: 7000
    property real maxOvershoot: 90

    property real velocity: 0
    // Below this speed the remaining travel is under half a pixel.
    readonly property real restSpeed: 0.5 / friction

    readonly property real lowBound: horizontal ? view.originX : view.originY - view.topMargin
    readonly property real highBound: Math.max(lowBound, horizontal
            ? view.originX + view.contentWidth + view.rightMargin - view.width
            : view.originY + view.contentHeight + view.bottomMargin - view.height)

    function offset() {
        return root.horizontal ? root.view.contentX : root.view.contentY;
    }

    function place(value) {
        if (root.horizontal)
            root.view.contentX = value;
        else
            root.view.contentY = value;
    }

    // delta is the distance in pixels the content should travel; bounce lets the end overshoot.
    function fling(delta, bounce) {
        if (!root.running)
            root.velocity = 0;
        let travel = delta;
        if (!bounce) {
            const rest = root.offset() + root.velocity * root.friction;
            travel = Math.max(root.lowBound, Math.min(rest + delta, root.highBound)) - rest;
        }
        root.velocity = Math.max(-root.maxVelocity, Math.min(root.velocity + travel / root.friction, root.maxVelocity));
        if (!root.running)
            root.start();
    }

    onTriggered: {
        const dt = Math.min(root.frameTime, 0.05);
        let position = root.offset();
        const edge = position < root.lowBound ? root.lowBound : position > root.highBound ? root.highBound : position;
        const over = position - edge;
        if (over === 0) {
            root.velocity *= Math.exp(-dt / root.friction);
            if (Math.abs(root.velocity) < root.restSpeed) {
                root.velocity = 0;
                root.stop();
                return;
            }
            position += root.velocity * dt;
        } else {
            // Critically damped return; velocity pointing away from the edge dies fast.
            const decay = Math.exp(-root.omega * dt);
            const carry = (root.velocity + root.omega * over) * dt;
            const next = (over + carry) * decay;
            root.velocity = (root.velocity - root.omega * carry) * decay;
            if (Math.abs(next) < 0.3 && Math.abs(root.velocity) < root.restSpeed) {
                root.place(edge);
                root.velocity = 0;
                root.stop();
                return;
            }
            position = edge + Math.max(-root.maxOvershoot, Math.min(next, root.maxOvershoot));
        }
        root.place(position);
    }

    // Interrupted glides (drag, relayout) never leave the content parked past an edge.
    onRunningChanged: {
        if (running)
            return;
        velocity = 0;
        const position = offset();
        if (position < lowBound)
            place(lowBound);
        else if (position > highBound)
            place(highBound);
    }
}
