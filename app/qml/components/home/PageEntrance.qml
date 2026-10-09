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

// Fades a page in and lifts it into place. One instance serves every page,
// since only one is visible at a time.
Item {
    id: root

    property Item target: null

    function play(page) {
        if (!page)
            return;
        entrance.stop();
        // A page abandoned mid-entrance stays visible-ready for its next turn.
        if (root.target && root.target !== page)
            root.target.opacity = 1;
        root.target = page;
        if (page.transform.length === 0)
            page.transform = [shift];
        page.opacity = 0;
        shift.y = Motion.rise;
        entrance.start();
    }

    visible: false

    Translate {
        id: shift
    }

    ParallelAnimation {
        id: entrance

        // GUI-thread animation so the scene layer and glass captures repaint with it.
        NumberAnimation {
            target: root.target
            property: "opacity"
            to: 1
            duration: Motion.normal
            easing.type: Motion.enter
        }

        NumberAnimation {
            target: shift
            property: "y"
            to: 0
            duration: Motion.slow
            easing.type: Motion.enter
        }
    }
}
