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


import Orchard
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
import QtMultimedia

// Full-window music video. Controls float over the picture and fade out while it plays.
Rectangle {
    id: root

    readonly property color accentColor: "#f0eee7"
    readonly property real barHeight: 120
    property bool upNextOpen: false
    property bool chromeShown: true
    // One automatic re-resolve per stream; signed URLs expire mid-session.
    property bool retried: false
    // Controls stay up while something on them is in use or the picture is not playing.
    readonly property bool chromeHeld: upNextOpen || bottomBar.menuOpen || topHover.hovered || bottomHover.hovered
                                      || !OrchardPlayback.playing || OrchardMusicVideo.status !== "ready"

    color: "#000000"
    focus: true

    function syncVideo() {
        if (video.mediaStatus === MediaPlayer.NoMedia || video.mediaStatus === MediaPlayer.LoadingMedia)
            return;
        const target = OrchardPlayback.position * 1000;
        // The audio engine plays the soundtrack and is the clock; the muted picture follows it.
        if (Math.abs(video.position - target) > 400)
            video.position = target;
        if (OrchardPlayback.playing && video.playbackState !== MediaPlayer.PlayingState)
            video.play();
        else if (!OrchardPlayback.playing && video.playbackState === MediaPlayer.PlayingState)
            video.pause();
    }

    function wake() {
        root.chromeShown = true;
        idleTimer.restart();
    }

    function toggleFullScreen() {
        const win = root.Window.window;
        if (win)
            win.visibility === Window.FullScreen ? win.showNormal() : win.showFullScreen();
    }

    onChromeHeldChanged: if (!chromeHeld) idleTimer.restart()

    Timer {
        id: idleTimer

        interval: 2500
        onTriggered: if (!root.chromeHeld) root.chromeShown = false
    }

    // Passive, so it sees pointer motion even over the controls.
    HoverHandler {
        property point last: Qt.point(-1, -1)

        cursorShape: root.chromeShown ? Qt.ArrowCursor : Qt.BlankCursor
        onPointChanged: {
            const pos = point.scenePosition;
            // Animated items resend hover under a still cursor; only real movement counts.
            if (Math.abs(pos.x - last.x) < 1 && Math.abs(pos.y - last.y) < 1)
                return;
            last = pos;
            root.wake();
        }
    }

    Shortcut {
        sequence: "Esc"
        onActivated: {
            if (root.Window.window && root.Window.window.visibility === Window.FullScreen)
                root.Window.window.showNormal();
            else
                OrchardMusicVideo.hide();
        }
    }

    MediaPlayer {
        id: video

        source: OrchardMusicVideo.source
        videoOutput: picture.output
        onSourceChanged: root.retried = false
        onMediaStatusChanged: if (mediaStatus === MediaPlayer.LoadedMedia || mediaStatus === MediaPlayer.BufferedMedia) root.syncVideo()
        onErrorOccurred: {
            if (!root.retried) {
                root.retried = true;
                OrchardMusicVideo.retry();
            }
        }
    }

    Timer {
        interval: 250
        repeat: true
        running: OrchardMusicVideo.status === "ready"
        onTriggered: root.syncVideo()
    }

    TheaterPicture {
        id: picture

        anchors.fill: parent
        visible: OrchardMusicVideo.status === "ready"

        // Single tap waits out the double-tap window so a double click never blips the audio.
        TapHandler {
            onSingleTapped: OrchardPlayback.toggle()
            onDoubleTapped: root.toggleFullScreen()
        }
    }

    BusyIndicator {
        anchors.centerIn: parent
        running: OrchardMusicVideo.status === "checking" || OrchardMusicVideo.status === "loading"
                 || (OrchardMusicVideo.status === "ready" && video.mediaStatus === MediaPlayer.LoadingMedia)
                 || video.mediaStatus === MediaPlayer.StalledMedia
    }

    ColumnLayout {
        anchors.centerIn: parent
        visible: OrchardMusicVideo.status === "unavailable" || OrchardMusicVideo.status === "error"
        spacing: 14

        Label {
            Layout.alignment: Qt.AlignHCenter
            text: OrchardMusicVideo.status === "error" ? qsTr("The video couldn't be played.")
                                                       : qsTr("No music video for this song.")
            color: "#f7f5f0"
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }

        Label {
            Layout.alignment: Qt.AlignHCenter
            Layout.maximumWidth: 480
            visible: text.length > 0
            text: OrchardMusicVideo.errorMessage
            color: "#8d928a"
            font.pixelSize: 13
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
        }

        Button {
            id: recoverButton

            Layout.alignment: Qt.AlignHCenter
            text: OrchardMusicVideo.status === "error" ? qsTr("Try again") : qsTr("Back to song")
            onClicked: OrchardMusicVideo.status === "error" ? OrchardMusicVideo.retry() : OrchardMusicVideo.hide()
            background: Rectangle { radius: 20; implicitHeight: 40; color: root.accentColor }
            contentItem: Label { text: recoverButton.text; color: "#0b0d0a"; font.pixelSize: 13; font.weight: Font.DemiBold; leftPadding: 18; rightPadding: 18; verticalAlignment: Text.AlignVCenter }
        }
    }

    // Shown even while the controls are hidden, like a player's own skip offer.
    TheaterSkipButton {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: root.upNextOpen ? 408 : 40
        anchors.bottomMargin: root.chromeShown ? root.barHeight + 24 : 40

        Behavior on anchors.bottomMargin { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
    }

    TheaterUpNext {
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.topMargin: root.barHeight
        anchors.bottomMargin: root.barHeight
        width: 380
        visible: root.upNextOpen
        onCloseRequested: root.upNextOpen = false
    }

    // Upper controls on a scrim that keeps them legible over bright footage.
    Item {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: root.barHeight + 40
        opacity: root.chromeShown ? 1 : 0
        visible: opacity > 0.01

        Behavior on opacity { NumberAnimation { duration: 250 } }

        HoverHandler { id: topHover }

        Rectangle {
            anchors.fill: parent
            gradient: Gradient {
                GradientStop { position: 0; color: "#b3000000" }
                GradientStop { position: 1; color: "#00000000" }
            }
        }

        TheaterTopBar {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.leftMargin: 40
            anchors.rightMargin: 40
            height: root.barHeight
            accentColor: root.accentColor
            upNextOpen: root.upNextOpen
            onUpNextToggled: root.upNextOpen = !root.upNextOpen
        }
    }

    Item {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: root.barHeight + 60
        opacity: root.chromeShown ? 1 : 0
        visible: opacity > 0.01

        Behavior on opacity { NumberAnimation { duration: 250 } }

        HoverHandler { id: bottomHover }

        Rectangle {
            anchors.fill: parent
            gradient: Gradient {
                GradientStop { position: 0; color: "#00000000" }
                GradientStop { position: 1; color: "#cc000000" }
            }
        }

        TheaterBottomBar {
            id: bottomBar

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: root.barHeight
            accentColor: root.accentColor
        }
    }
}
