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

// Spotify Canvas connection card. Layout lives in SpotifyForm.ui.qml.
SpotifyForm {
    id: root

    status: OrchardSpotify.status
    loginAvailable: OrchardSpotify.loginAvailable
    connected: OrchardSpotify.connected
    message: OrchardSpotify.message
    messageIsError: OrchardSpotify.messageIsError

    function saveCookie() {
        if (OrchardSpotify.saveCookie(cookieField.text)) {
            cookieField.clear();
            root.enteringCookie = false;
        }
    }

    loginButton.onClicked: OrchardSpotify.connectAccount()
    cookieToggleButton.onClicked: root.enteringCookie = !root.enteringCookie
    cancelButton.onClicked: OrchardSpotify.cancelConnection()
    disconnectButton.onClicked: OrchardSpotify.disconnectAccount()
    cookieField.onAccepted: root.saveCookie()
    saveButton.enabled: cookieField.text.trim().length > 0
    saveButton.onClicked: root.saveCookie()
}
