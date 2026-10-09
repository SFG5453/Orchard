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

// Last.fm scrobbling card. Layout lives in LastFmForm.ui.qml.
LastFmForm {
    readonly property var lastfm: OrchardIntegrations.lastfm

    status: lastfm.status
    scrobbling: lastfm.enabled
    connected: lastfm.connected
    user: lastfm.user
    message: lastfm.message
    messageIsError: lastfm.messageIsError

    scrobbleSwitch.onToggled: lastfm.enabled = scrobbleSwitch.checked
    connectButton.onClicked: lastfm.connectAccount()
    finishButton.onClicked: lastfm.completeConnection()
    cancelButton.onClicked: lastfm.cancelConnection()
    disconnectButton.onClicked: lastfm.disconnectAccount()
}
