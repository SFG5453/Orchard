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

// Qobuz connection card. Layout lives in QobuzForm.ui.qml.
QobuzForm {
    id: root

    status: OrchardQobuz.status
    active: OrchardQobuz.active
    connected: OrchardQobuz.connected
    message: OrchardQobuz.message
    messageIsError: OrchardQobuz.messageIsError

    onStatusChanged: if (status !== "connecting") tokenPending = false

    function signInWithToken() {
        if (!tokenSaveButton.enabled)
            return;
        root.tokenPending = true;
        OrchardQobuz.connectWithToken(userIdField.text, tokenField.text, appIdField.text, appSecretField.text);
        tokenField.clear();
        appSecretField.clear();
        root.enteringToken = false;
    }

    connectButton.onClicked: OrchardQobuz.connectAccount()
    tokenToggleButton.onClicked: root.enteringToken = !root.enteringToken
    userIdField.onAccepted: root.signInWithToken()
    tokenField.onAccepted: root.signInWithToken()
    appIdField.onAccepted: root.signInWithToken()
    appSecretField.onAccepted: root.signInWithToken()
    // App credentials are optional, but only as a pair.
    tokenSaveButton.enabled: userIdField.text.trim().length > 0 && tokenField.text.trim().length > 0
        && (appIdField.text.trim().length > 0) === (appSecretField.text.trim().length > 0)
    tokenSaveButton.onClicked: root.signInWithToken()
    cancelButton.onClicked: OrchardQobuz.cancelConnection()
    disconnectButton.onClicked: OrchardQobuz.disconnectAccount()
}
