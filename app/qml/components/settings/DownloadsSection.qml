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

// Download settings. Layout lives in DownloadsSectionForm.ui.qml.
DownloadsSectionForm {
    id: root

    quality: OrchardDownloads.quality
    animatedArtwork: OrchardDownloads.animatedArtwork
    animatedArtworkAppearanceOn: OrchardAppearance.animatedArtworkEnabled
    downloadCount: OrchardDownloads.count
    countTitle: OrchardDownloads.count === 1 ? qsTr("1 downloaded song")
        : qsTr("%1 downloaded songs").arg(OrchardDownloads.count.toLocaleString(Qt.locale(), "f", 0))
    usedText: OrchardDownloads.formatBytes(OrchardDownloads.bytesUsed)
    pending: OrchardDownloads.pending
    failedCount: OrchardDownloads.failedCount
    currentTitle: OrchardDownloads.currentTitle
    progress: OrchardDownloads.progress
    offline: OrchardNetwork.offline

    qualityPicker.onPicked: value => OrchardDownloads.quality = value
    clearButton.onClicked: clearWarning.open()
    retryButton.onClicked: OrchardDownloads.retryFailed()

    animatedSwitch.onToggled: {
        // Turning it on waits for the bandwidth warning to be accepted.
        if (animatedSwitch.checked) {
            animatedSwitch.checked = Qt.binding(() => root.animatedArtwork);
            animatedWarning.open();
        } else {
            OrchardDownloads.animatedArtwork = false;
        }
    }

    ConfirmDialog {
        id: animatedWarning
        title: qsTr("Download animated artwork?")
        message: qsTr("Animated covers are short videos. They can be several megabytes for each album and are saved next to every song you download, so expect a lot more bandwidth and disk use. Songs without a loop are unaffected.")
        confirmText: qsTr("Turn on")
        onAccepted: OrchardDownloads.animatedArtwork = true
    }

    ConfirmDialog {
        id: clearWarning
        title: qsTr("Clear all downloads?")
        message: qsTr("This deletes every downloaded song and saved playlist from this computer, and stops downloads in progress. You can download them again while online.")
        confirmText: qsTr("Clear all")
        destructive: true
        onAccepted: OrchardDownloads.clearAll()
    }
}
