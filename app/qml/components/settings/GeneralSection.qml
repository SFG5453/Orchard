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

// Launch behavior, updates and accounts. Layout lives in GeneralSectionForm.ui.qml.
GeneralSectionForm {
    id: root

    signal signOutRequested

    trayAvailable: OrchardTray.available
    signedIn: OrchardAuth.isSignedIn
    authError: OrchardAuth.errorMessage

    persistSwitch.checked: OrchardPlayback.playbackPersistenceEnabled
    persistSwitch.onToggled: OrchardPlayback.playbackPersistenceEnabled = persistSwitch.checked

    windowSwitch.checked: OrchardWindowState.enabled
    windowSwitch.onToggled: OrchardWindowState.enabled = windowSwitch.checked

    traySwitch.checked: OrchardTray.closeToTray
    traySwitch.onToggled: OrchardTray.closeToTray = traySwitch.checked

    historySwitch.checked: OrchardPlayback.youtubeHistoryEnabled
    historySwitch.onToggled: OrchardPlayback.youtubeHistoryEnabled = historySwitch.checked

    account.onSignOutRequested: root.signOutRequested()

    function megabytes(bytes) {
        return (bytes / 1048576).toFixed(1) + " MB";
    }

    function updateTitleFor(state, latest) {
        switch (state) {
        case "ready": return qsTr("Update ready. Restart Orchard to install.");
        case "checking": return qsTr("Checking for updates");
        case "downloading": return qsTr("Downloading Orchard %1").arg(latest);
        case "staging": return qsTr("Installing update");
        case "error": return qsTr("Update failed");
        case "cancelled": return qsTr("Update cancelled");
        default: return qsTr("Orchard is up to date");
        }
    }

    function updateDetailFor(state, current, latest, channel, done, total, error) {
        switch (state) {
        case "ready": return qsTr("Orchard %1 is downloaded and verified.").arg(latest);
        case "downloading": return total > 0 ? qsTr("%1 of %2").arg(root.megabytes(done)).arg(root.megabytes(total)) : "";
        case "staging": return qsTr("Putting files in place next to the running version.");
        case "error": return error;
        default: return qsTr("Version %1 on the %2 channel").arg(current).arg(channel);
        }
    }

    function updateActionFor(state) {
        switch (state) {
        case "ready": return qsTr("Restart");
        case "downloading": return qsTr("Cancel");
        case "checking":
        case "staging": return qsTr("Please wait");
        case "error": return qsTr("Try again");
        default: return qsTr("Check for updates");
        }
    }

    updatesAvailable: OrchardUpdates.available
    updateTitle: root.updateTitleFor(OrchardUpdates.state, OrchardUpdates.latestVersion)
    updateDetail: root.updateDetailFor(OrchardUpdates.state, OrchardUpdates.currentVersion, OrchardUpdates.latestVersion,
                                  OrchardUpdates.channel, OrchardUpdates.downloaded, OrchardUpdates.total,
                                  OrchardUpdates.error)
    updateActionText: root.updateActionFor(OrchardUpdates.state)
    updateActionEnabled: OrchardUpdates.state !== "checking" && OrchardUpdates.state !== "staging"
    updateBusy: OrchardUpdates.state === "downloading" || OrchardUpdates.state === "staging"
    updateProgress: OrchardUpdates.total > 0 ? OrchardUpdates.downloaded / OrchardUpdates.total : 0

    updateButton.onClicked: {
        if (OrchardUpdates.state === "ready")
            OrchardUpdates.restartToUpdate();
        else if (OrchardUpdates.state === "downloading")
            OrchardUpdates.cancelDownload();
        else
            OrchardUpdates.checkForUpdates();
    }

    autoUpdateSwitch.checked: OrchardUpdates.autoUpdate
    autoUpdateSwitch.onToggled: OrchardUpdates.setAutoUpdate(autoUpdateSwitch.checked)

    canarySwitch.checked: OrchardUpdates.channel === "canary"
    canarySwitch.onToggled: OrchardUpdates.setChannel(canarySwitch.checked ? "canary" : "stable")
}
