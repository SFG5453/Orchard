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
import Orchard
import "../settings"

WelcomePage {
    title: qsTr("Fit it to your desktop")
    subtitle: qsTr("Control how Orchard starts, closes and updates.")

    WelcomeRow {
        title: qsTr("Restore last session")
        hint: qsTr("Reopen with your last queue, song and page.")

        SettingsSwitch {
            checked: OrchardPlayback.playbackPersistenceEnabled
            onToggled: OrchardPlayback.playbackPersistenceEnabled = checked
            Accessible.name: qsTr("Restore last session")
        }
    }

    WelcomeRow {
        title: qsTr("Remember window size")
        hint: qsTr("Reopen Orchard at the size you last used.")

        SettingsSwitch {
            checked: OrchardWindowState.enabled
            onToggled: OrchardWindowState.enabled = checked
            Accessible.name: qsTr("Remember window size")
        }
    }

    WelcomeRow {
        visible: OrchardTray.available
        title: qsTr("Keep playing in the tray")
        hint: qsTr("Closing the window leaves Orchard running in the tray.")

        SettingsSwitch {
            checked: OrchardTray.closeToTray
            onToggled: OrchardTray.closeToTray = checked
            Accessible.name: qsTr("Close to system tray")
        }
    }

    WelcomeRow {
        title: qsTr("Download updates automatically")
        hint: qsTr("New versions install the next time Orchard starts.")

        SettingsSwitch {
            checked: OrchardUpdates.autoUpdate
            onToggled: OrchardUpdates.setAutoUpdate(checked)
            Accessible.name: qsTr("Download updates automatically")
        }
    }
}
