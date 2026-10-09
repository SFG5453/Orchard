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

// Discord Rich Presence card. Layout lives in DiscordForm.ui.qml.
DiscordForm {
    id: root

    discord: OrchardIntegrations.discord
    accountSignedIn: OrchardAccount.isSignedIn

    enableSwitch.onToggled: discord.enabled = enableSwitch.checked
    lyricsSwitch.onToggled: discord.showSyncedLyrics = lyricsSwitch.checked
    projectSwitch.onToggled: discord.projectButtonEnabled = projectSwitch.checked
    animatedSwitch.onToggled: discord.animatedArtworkEnabled = animatedSwitch.checked

    activityField.onTextChanged: {
        if (activityField.activeFocus)
            discord.activityText = activityField.text;
    }
    detailsField.onTextChanged: {
        if (detailsField.activeFocus)
            discord.detailsText = detailsField.text;
    }
    stateField.onTextChanged: {
        if (stateField.activeFocus)
            discord.stateText = stateField.text;
    }

    platformSelect.currentIndex: platformValues.indexOf(discord.platform)
    platformSelect.onActivated: index => discord.platform = platformValues[index]
    activitySelect.currentIndex: activityValues.indexOf(discord.activityType)
    activitySelect.onActivated: index => discord.activityType = activityValues[index]
    statusSelect.currentIndex: statusDisplayValues.indexOf(discord.statusDisplay)
    statusSelect.onActivated: index => discord.statusDisplay = statusDisplayValues[index]
}
