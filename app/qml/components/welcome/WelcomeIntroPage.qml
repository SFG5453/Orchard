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

WelcomePage {
    readonly property string firstName: (OrchardAuth.userName || "").split(" ")[0]

    title: firstName ? qsTr("Welcome, %1").arg(firstName) : qsTr("Welcome to Orchard")
    subtitle: qsTr("A few quick choices to set Orchard up your way. Everything here can be changed later in Settings.")

    WelcomeRow {
        title: qsTr("Appearance")
        hint: qsTr("Player layout, artwork and background.")
    }

    WelcomeRow {
        title: qsTr("Playback")
        hint: qsTr("Stream quality, crossfade and history.")
    }

    WelcomeRow {
        title: qsTr("Library")
        hint: qsTr("Download quality and playlist covers.")
    }

    WelcomeRow {
        title: qsTr("Connections")
        hint: qsTr("Last.fm, Discord, Spotify and Qobuz.")
    }

    WelcomeRow {
        title: qsTr("System")
        hint: qsTr("Startup, tray and updates.")
    }
}
