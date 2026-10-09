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

// Report a bug. The complaints department, now with a ticket number.
GlassPopup {
    id: root

    // Report to show on open; empty opens the list on a new report.
    property string reportId: ""

    // The shell hides this popup, lets you walk to the broken screen, then reopens it.
    signal captureRequested

    width: Math.min(1060, parent.width - 64)
    height: Math.min(740, parent.height - 64)

    onOpened: OrchardSupport.refresh()

    contentItem: SupportView {
        initialReport: root.reportId
        onCloseRequested: root.close()
        onCaptureRequested: root.captureRequested()
    }
}
