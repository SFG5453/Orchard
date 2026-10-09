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

// Section panel with a staggered entrance. Layout lives in SettingsSectionForm.ui.qml.
SettingsSectionForm {
    id: root

    // Panels rise in one after another when a section loads. The Mexican wave of settings.
    Component.onCompleted: {
        let order = 0;
        for (const sibling of parent ? parent.children : []) {
            if (sibling === root)
                break;
            if (sibling.visible)
                order++;
        }
        opacity = 0;
        slide.y = 18;
        entrance.delay = Math.min(order, 6) * 45;
        entrance.start();
    }
}
