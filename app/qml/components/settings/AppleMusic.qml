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

AppleMusicForm {
    status: OrchardAppleMusic.status
    connected: OrchardAppleMusic.connected
    loginAvailable: OrchardAppleMusic.loginAvailable
    accountSignedIn: OrchardAccount.isSignedIn
    message: OrchardAppleMusic.message
    messageIsError: OrchardAppleMusic.messageIsError

    connectButton.onClicked: OrchardAppleMusic.connectAccount()
    cancelButton.onClicked: OrchardAppleMusic.cancelConnection()
    disconnectButton.onClicked: OrchardAppleMusic.disconnectAccount()
    historySwitch.checked: OrchardAppleMusic.playHistoryEnabled
    historySwitch.onToggled: OrchardAppleMusic.playHistoryEnabled = historySwitch.checked
}
