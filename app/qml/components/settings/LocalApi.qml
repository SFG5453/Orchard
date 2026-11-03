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

// Local API card. Layout lives in LocalApiForm.ui.qml.
LocalApiForm {
    id: card

    function applyPort() {
        const value = parseInt(portField.text, 10);
        portInvalid = !OrchardApi.setPort(value);
        if (!portInvalid)
            portField.text = "" + OrchardApi.port;
    }

    apiEnabled: OrchardApi.enabled
    running: OrchardApi.running
    port: OrchardApi.port
    error: OrchardApi.error

    enableSwitch.onToggled: OrchardApi.enabled = enableSwitch.checked
    portField.onAccepted: applyPort()
    portField.onTextEdited: portInvalid = false
    applyButton.onClicked: applyPort()
}
