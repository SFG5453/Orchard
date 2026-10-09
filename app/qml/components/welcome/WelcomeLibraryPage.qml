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
    title: qsTr("Your library")
    subtitle: qsTr("Decide how downloads and playlist covers behave.")

    WelcomeRow {
        title: qsTr("Download quality")
        hint: qsTr("Applies to songs you save for offline listening.")

        SettingsSegmented {
            model: [
                { value: "saver", label: qsTr("Saver"), name: qsTr("Saver download quality") },
                { value: "normal", label: qsTr("Normal"), name: qsTr("Normal download quality") },
                { value: "high", label: qsTr("High"), name: qsTr("High download quality") }
            ]
            currentValue: OrchardDownloads.quality
            onPicked: value => OrchardDownloads.quality = value
        }
    }

    WelcomeRow {
        title: qsTr("Animate playlist collages")
        hint: qsTr("Play motion artwork in playlist covers. Uses more CPU and GPU.")

        SettingsSwitch {
            checked: OrchardAppearance.animatedCollageEnabled
            onToggled: OrchardAppearance.animatedCollageEnabled = checked
            Accessible.name: qsTr("Animate playlist collages")
        }
    }

    WelcomeRow {
        title: qsTr("Downloads")
        hint: qsTr("%n song(s) on this computer", "", OrchardDownloads.count)
    }
}
