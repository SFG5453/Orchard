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

WelcomePage {
    signal docsRequested

    title: qsTr("You're all set")
    subtitle: qsTr("A few shortcuts to get around. Settings and docs are always one click away.")

    WelcomeRow {
        title: qsTr("Search")
        hint: qsTr("Press / or Ctrl+K from anywhere.")
    }

    WelcomeRow {
        title: qsTr("Play and pause")
        hint: qsTr("Press Space. Left and right arrows seek.")
    }

    WelcomeRow {
        title: qsTr("Fullscreen player")
        hint: qsTr("Press F while a song is playing.")
    }

    WelcomeRow {
        title: qsTr("Settings and docs")
        hint: qsTr("Revisit any choice from this tour.")

        Row {
            spacing: 8

            WelcomeButton {
                compact: true
                text: qsTr("Settings")
                onClicked: settingsRequested()
            }

            WelcomeButton {
                compact: true
                text: qsTr("Docs")
                onClicked: docsRequested()
            }
        }
    }
}
