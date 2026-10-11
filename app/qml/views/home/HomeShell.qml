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

import "../../components"
import "../../components/home"
import "../../components/player"
import Orchard
import QtQuick
import QtQuick.Window

Item {
    id: root

    property bool queueOpen: false
    property bool queuePanelRetained: false
    property bool fullscreenOpen: false
    property bool fullscreenRetained: false
    property rect fullscreenOrigin: Qt.rect(0, 0, 44, 44)
    // Window state to return to when the player closes; -1 when the player didn't change it.
    property int visibilityBeforeFullscreen: -1
    // Windowed size to put back when fullscreen ends; the compositor may hand back a stale one.
    property size sizeBeforeFullscreen: Qt.size(0, 0)
    // Scene and pill are fully hidden behind the player, so skip drawing them.
    readonly property bool fullscreenCovering: fullscreenLoader.item !== null && fullscreenLoader.item.covering
    property string currentPage: "home"
    property string searchQuery: ""
    property string searchFilter: "all"
    property string noticeMessage: ""
    readonly property alias searchDebounce: searchDebounce
    property var history: []
    property var currentItem: ({
    })
    readonly property bool canopy: OrchardAppearance.layoutStyle === "canopy"
    readonly property bool immersiveReady: OrchardAppearance.immersiveBackground && scene.immersive.ready

    function playPlaylist(index, shuffle) {
        // Unloaded pages arrive in playlist order, so a sorted queue stops at what is loaded.
        const continuation = OrchardPlaylist.sortKey ? "" : OrchardPlaylist.detail.continuation || "";
        OrchardPlayback.playCollection(OrchardPlaylist.detail.tracks || [], index, shuffle, OrchardPlaylist.detail.browseId || "", continuation);
    }

    function showNotice(message) {
        root.noticeMessage = message;
        noticeTimer.restart();
    }

    function openSettings() { overlays.openSettings(); }
    function openDocs(page) { overlays.openDocs(page); }
    function openSupport(reportId) { overlays.openSupport(reportId); }
    function openSpotlight(query) { overlays.openSpotlight(query); }

    function navigateTo(page) {
        searchDebounce.stop();
        root.history = [];
        root.currentItem = ({
        });
        if (page !== "search") {
            root.searchFilter = "all";
            OrchardSearch.clear();
            scene.topBar.clearSearch();
        }
        root.currentPage = page;
        if (page === "search") {
            scene.topBar.focusSearch();
            if (root.searchQuery.trim())
                searchDebounce.restart();

        }
    }

    function detailKey(item) {
        if (!item)
            return "";
        return item.browseId || (item.browsePayload || {}).browseId || item.playlistId || "";
    }

    function openDetail(page, item) {
        // Re-opening the visible page would push a duplicate history entry and replay the load.
        const key = detailKey(item);
        if (key && page === currentPage && key === detailKey(currentItem))
            return ;
        history = history.concat([{
            "page": currentPage,
            "item": currentItem
        }]);
        showDetail(page, item);
    }

    function showDetail(page, item) {
        currentItem = item;
        currentPage = page;
        if (page === "artist")
            OrchardArtist.openAlbum(item);

        if (page === "album")
            OrchardAlbum.openAlbum(item);

        if (page === "playlist") {
            OrchardPlaylist.openPlaylist(item);
            Qt.callLater(() => {
                if (scene.playlistLoader.item)
                    scene.playlistLoader.item.forceActiveFocus();
            });
        }
    }

    // Back from a restored detail lands on home.
    function restorePage(saved) {
        const page = saved.page;
        if (page === "library") {
            navigateTo(page);
        } else if (page === "search") {
            searchFilter = saved.filter || "all";
            currentPage = page;
            scene.topBar.query = saved.query || "";
        } else if ((page === "album" || page === "artist" || page === "playlist") && detailKey(saved.item)) {
            showDetail(page, saved.item);
        }
    }

    function pageItem(page) {
        return ({ home: OrchardNetwork.offline ? scene.offlineHomeLoader : scene.home, library: scene.libraryLoader, search: scene.searchLoader,
                  album: scene.albumLoader, artist: scene.artistLoader, playlist: scene.playlistLoader })[page] || null;
    }

    // Results come from YouTube online and from downloads offline, so they restart when that flips.
    function rerunSearch() {
        OrchardSearch.clear();
        if (root.currentPage === "search" && root.searchQuery.trim())
            searchDebounce.restart();
    }

    function goBack() {
        if (!history.length) {
            currentPage = "home";
            return ;
        }
        const previous = history[history.length - 1];
        history = history.slice(0, -1);
        showDetail(previous.page, previous.item);
    }

    // Re-measure on both edges so the cover flies to wherever the pill sits right now.
    function measureFullscreenOrigin() {
        const art = player.artworkItem;
        const origin = art.mapToItem(root, 0, 0);
        root.fullscreenOrigin = Qt.rect(origin.x, origin.y, art.width, art.height);
    }

    // The window follows the player into fullscreen, and only leaves it if the player put it there.
    function setFullscreen(open) {
        const win = root.Window.window;
        if (win && open && win.visibility !== Window.FullScreen) {
            root.visibilityBeforeFullscreen = win.visibility;
            if (win.visibility === Window.Windowed)
                root.sizeBeforeFullscreen = Qt.size(win.width, win.height);
            win.showFullScreen();
        } else if (win && !open && root.visibilityBeforeFullscreen !== -1) {
            if (win.visibility === Window.FullScreen) {
                if (root.visibilityBeforeFullscreen === Window.Maximized) {
                    // Go through Windowed so the compositor issues a fresh maximize configure.
                    win.showNormal();
                    restoreMaximize.restart();
                } else {
                    win.showNormal();
                    root.restoreWindowedSize(win);
                }
            }
            root.visibilityBeforeFullscreen = -1;
        }
        root.measureFullscreenOrigin();
        root.fullscreenOpen = open;
    }

    function restoreWindowedSize(win) {
        const size = root.sizeBeforeFullscreen;
        if (size.width <= 0 || size.height <= 0)
            return;
        win.width = size.width;
        win.height = size.height;
        // Some compositors apply their own size after the state change lands.
        restoreSize.restart();
    }

    function openFromFullscreen(page, item) {
        root.setFullscreen(false);
        root.openDetail(page, item);
    }

    function isEditableFocused() {
        const item = root.Window.window ? root.Window.window.activeFocusItem : null;
        if (!item)
            return false;

        return item.cursorPosition !== undefined || item.inputMethodHints !== undefined;
    }

    // The window resize lands mid-flight and moves the pill; keep the cover aimed at it.
    onWidthChanged: if (fullscreenRetained) Qt.callLater(measureFullscreenOrigin)
    onHeightChanged: if (fullscreenRetained) Qt.callLater(measureFullscreenOrigin)
    // Detail-to-detail hops keep the page but swap the item, so both replay the entrance.
    onCurrentPageChanged: pageEntrance.play(pageItem(currentPage))
    onCurrentItemChanged: pageEntrance.play(pageItem(currentPage))
    onFullscreenOpenChanged: {
        if (fullscreenOpen) {
            fullscreenRetained = true;
            fullscreenReleaseTimer.stop();
        } else {
            fullscreenReleaseTimer.restart();
        }
    }
    onQueueOpenChanged: {
        if (queueOpen) {
            queuePanelRetained = true;
            queueReleaseTimer.stop();
            Qt.callLater(() => {
                if (queuePanelLoader.item)
                    queuePanelLoader.item.forceActiveFocus();
            });
        } else {
            queueReleaseTimer.restart();
        }
    }
    anchors.fill: parent
    Keys.onPressed: function(event) {
        if (root.currentPage === "playlist" && scene.playlistLoader.item)
            scene.playlistLoader.item.handleKeyPress(event);

    }
    Component.onCompleted: {
        if (OrchardAuth.isSignedIn)
            OrchardHome.refresh();

    }

    PageMemory {
        page: root.currentPage
        item: root.currentItem
        query: root.searchQuery
        filter: root.searchFilter
        onRestoreRequested: function(saved) {
            root.restorePage(saved);
        }
    }

    Timer {
        id: restoreMaximize
        // Gives fullscreen a moment to let go before we ask for the big window again.
        interval: 100
        onTriggered: {
            const win = root.Window.window;
            // Skip if the player reopened fullscreen in the meantime.
            if (win && win.visibility === Window.Windowed && !root.fullscreenOpen)
                win.showMaximized();
        }
    }

    Timer {
        id: restoreSize
        interval: 150
        onTriggered: {
            const win = root.Window.window;
            const size = root.sizeBeforeFullscreen;
            if (win && win.visibility === Window.Windowed && size.width > 0) {
                win.width = size.width;
                win.height = size.height;
            }
        }
    }

    Timer {
        id: searchDebounce

        interval: 320
        repeat: false
        onTriggered: {
            if (root.searchQuery.trim())
                OrchardSearch.search(root.searchQuery, root.searchFilter);

        }
    }

    Timer {
        id: noticeTimer

        interval: 3200
        repeat: false
        onTriggered: root.noticeMessage = ""
    }

    Timer {
        id: queueReleaseTimer
        // Outlives the panel's 340ms slide-out so it isn't unloaded mid-exit.
        interval: 400
        repeat: false
        onTriggered: root.queuePanelRetained = false
    }
    Timer {
        id: fullscreenReleaseTimer
        // Outlives the 620ms flight home.
        interval: 700
        repeat: false
        onTriggered: root.fullscreenRetained = false
    }

    // Queue lyrics and fullscreen lyrics share one fetcher.
    Binding {
        target: OrchardLyrics
        property: "active"
        value: (root.queueOpen && queuePanelLoader.item !== null && queuePanelLoader.item.lyricsMode)
               || (root.fullscreenOpen && fullscreenLoader.item !== null && fullscreenLoader.item.pane === "lyrics")
    }

    // Forward backend announcements (like copied links) straight into the UI pill.

    Connections {
        function onNoticeRequested(message) {
            root.showNotice(message);
        }

        target: OrchardBackend
    }

    PlaylistPicker {
        id: playlistPicker
    }

    Connections {
        target: OrchardNetwork

        // Album and artist pages need YouTube; the offline home is the way back.
        function onLost() {
            if (root.currentPage === "album" || root.currentPage === "artist")
                root.navigateTo("home");
            if (["videos", "albums", "artists"].includes(root.searchFilter))
                root.searchFilter = "all";
            root.rerunSearch();
            root.showNotice(qsTr("You're offline. Showing your downloads."));
        }

        function onRestored() {
            root.rerunSearch();
            root.showNotice(qsTr("Back online."));
            OrchardHome.refresh();
        }
    }

    PageEntrance {
        id: pageEntrance
    }

    Connections {
        function onNoticeRequested(message) {
            root.showNotice(message);
        }

        function onPlaylistPickerRequested(track, position) {
            playlistPicker.openFor(track, position);
        }

        target: OrchardLibrary
    }

    HomeScene {
        id: scene

        shell: root
        dockHeight: root.canopy ? 12 : player.height + 26
    }

    PlayerBar {
        id: player

        backdrop: scene
        // Pages scroll under the glass, so the capture tracks every repaint.
        backdropLive: true
        queueOpen: root.queueOpen
        visible: !root.fullscreenCovering
        onQueueRequested: root.queueOpen = !root.queueOpen
        onExpandRequested: root.setFullscreen(true)
        onAlbumRequested: function(album) {
            root.openDetail("album", album);
        }
        onArtistRequested: function(artist) {
            root.openDetail("artist", artist);
        }
        onPlaylistRequested: function(playlist) {
            root.openDetail("playlist", playlist);
        }
        // Glade: centered in the content column at the bottom. Canopy: top row, ending before the search box.
        x: root.canopy ? scene.topBar.x : (parent.width - width) / 2 + scene.sidebarWidth / 2
        y: root.canopy ? scene.topBar.y + (scene.topBar.height - height) / 2 : parent.height - height - 14
        width: root.canopy ? scene.topBar.canopyPlayerWidth : Math.min(parent.width - scene.sidebarWidth - 32, 1120)
        height: root.canopy ? 52 : 64
        z: 6
    }

    Loader {
        id: queuePanelLoader

        anchors.right: parent.right
        anchors.rightMargin: 14
        anchors.top: parent.top
        anchors.topMargin: root.canopy ? scene.topBar.y + scene.topBar.height + 8 : 40
        anchors.bottom: parent.bottom
        anchors.bottomMargin: scene.playerDock.height
        width: scene.queueWidth
        z: 7
        active: root.queueOpen || root.queuePanelRetained
        // Flip open after creation so the slide-in actually animates.
        onLoaded: Qt.callLater(() => {
            if (item)
                item.open = Qt.binding(() => root.queueOpen);
        })

        sourceComponent: Component {
            QueuePanel {
                backdrop: scene
                backdropLive: true
                motionArtwork: !root.fullscreenOpen
                accentColor: player.accentColor
                inkColor: player.inkColor
                enabled: root.queueOpen
                onCloseRequested: root.queueOpen = false
            }
        }
    }

    Loader {
        id: fullscreenLoader

        anchors.fill: parent
        z: 20
        active: root.fullscreenOpen || root.fullscreenRetained
        onLoaded: Qt.callLater(() => {
            if (item)
                item.open = Qt.binding(() => root.fullscreenOpen);
        })

        sourceComponent: Component {
            FullscreenPlayer {
                accentColor: player.accentColor
                mixAccentColor: player.mixAccentColor
                inkColor: player.inkColor
                originRect: root.fullscreenOrigin
                onCloseRequested: root.setFullscreen(false)
                onAlbumRequested: function(album) { root.openFromFullscreen("album", album); }
                onArtistRequested: function(artist) { root.openFromFullscreen("artist", artist); }
                onPlaylistRequested: function(playlist) { root.openFromFullscreen("playlist", playlist); }
            }
        }
    }

    // Detail scroll views pad their ends by this so the last row clears the pill.
    Binding {
        target: root.Window.window
        property: "playerInset"
        value: scene.playerDock.height
        when: root.Window.window !== null
    }

    HomeOverlays {
        id: overlays

        shell: root
        stage: scene
    }
}
