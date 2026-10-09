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
import ".."

// The manual, in its own window, because a handbook deserves more than a settings tab.
GlassPopup {
    id: root

    // Page id to open on; empty shows the first page.
    property string page: ""

    width: Math.min(1120, parent.width - 64)
    height: Math.min(760, parent.height - 64)

    onOpened: view.focusSearch()

    contentItem: DocsView {
        id: view
        initialPage: root.page
        onCloseRequested: root.close()
    }
}
