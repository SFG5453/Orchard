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
import "../../components/docs"
import "../../components/settings"
import "../../components/support"
import "../../components/welcome"
import Orchard
import QtQuick

// Settings, docs, spotlight and support popups plus the shell keyboard shortcuts.
Item {
    id: overlays

    // HomeShell: owns navigation state and the fullscreen player.
    required property Item shell
    // Glass backdrop the popups blur; also hosts the top bar search field.
    required property Item stage

    readonly property bool signedIn: OrchardAuth.isSignedIn
    readonly property bool welcomeOpen: welcomeLoader.active

    anchors.fill: parent
    z: 200

    function openWelcome() {
        welcomeLoader.active = true;
    }

    function maybeOpenWelcome() {
        if (overlays.signedIn && !OrchardAppearance.welcomeSeen)
            overlays.openWelcome();
    }

    onSignedInChanged: overlays.maybeOpenWelcome()
    Component.onCompleted: overlays.maybeOpenWelcome()

    function openSettings() {
        settingsLoader.active = true;
        Qt.callLater(() => {
            if (settingsLoader.item)
                settingsLoader.item.open();
        });
    }

    function openDocs(page) {
        docsLoader.page = page || "";
        docsLoader.active = true;
        Qt.callLater(() => {
            if (docsLoader.item)
                docsLoader.item.open();
        });
    }

    function openSupport(reportId) {
        const closing = overlays.overlayOpen(settingsLoader) || overlays.overlayOpen(spotlightLoader) || overlays.overlayOpen(docsLoader);
        supportHost.open(reportId || "", closing);
    }

    function openSpotlight(query) {
        spotlightLoader.active = true;
        Qt.callLater(() => {
            if (spotlightLoader.item)
                spotlightLoader.item.openSpotlight(query || "");
        });
    }

    function overlayOpen(loader) {
        return loader.item && loader.item.visible;
    }

    Loader {
        id: settingsLoader
        active: false

        sourceComponent: Component {
            SettingsPopup {
                backdrop: overlays.stage
                onClosed: settingsLoader.active = false
                onDocsRequested: {
                    overlays.openDocs();
                    close();
                }
                onSupportRequested: {
                    overlays.openSupport("");
                    close();
                }
            }
        }
    }

    Loader {
        id: welcomeLoader
        anchors.fill: parent
        active: false

        sourceComponent: Component {
            WelcomeFlow {
                onSettingsRequested: overlays.openSettings()
                onDocsRequested: overlays.openDocs()
                onFinished: {
                    OrchardAppearance.welcomeSeen = true;
                    welcomeLoader.active = false;
                }
            }
        }
    }

    Loader {
        id: docsLoader
        // Page id to open on; set before the loader activates.
        property string page: ""
        active: false

        sourceComponent: Component {
            DocsPopup {
                backdrop: overlays.stage
                page: docsLoader.page
                onClosed: docsLoader.active = false
            }
        }
    }

    Loader {
        id: spotlightLoader
        active: false

        sourceComponent: Component {
            SpotlightSearch {
                backdrop: overlays.stage
                onClosed: spotlightLoader.active = false
                onPageRequested: function(page) {
                    overlays.shell.navigateTo(page);
                }
                onSearchRequested: function(query) {
                    overlays.shell.searchQuery = query;
                    overlays.stage.topBar.query = query;
                    overlays.shell.history = [];
                    overlays.shell.currentItem = ({
                    });
                    overlays.shell.currentPage = "search";
                    OrchardSearch.search(query, "all");
                }
                onDetailRequested: function(page, item) {
                    overlays.shell.openDetail(page, item);
                }
                onSongRequested: function(song) {
                    OrchardPlayback.playSong(song);
                }
                onUnsupportedRequested: function(media) {
                    OrchardMusicVideo.playVideo(media);
                }
                onSettingsRequested: overlays.openSettings()
                onDocsRequested: overlays.openDocs()
                onSupportRequested: overlays.openSupport("")
                onQueueRequested: overlays.shell.queueOpen = true
            }
        }
    }

    SupportHost {
        id: supportHost
        backdrop: overlays.stage
        page: overlays.shell.currentPage
        z: 200
        onNotice: message => overlays.shell.showNotice(message)
    }

    // The sacred '/' shortcut. Don't trigger if the user is actually trying to type
    // AC/DC in an input box unless you want an angry mob with pitchforks.
    Shortcut {
        sequence: "/"
        enabled: overlays.shell.visible && !overlays.welcomeOpen && !overlays.shell.fullscreenOpen && !overlays.overlayOpen(spotlightLoader) && !overlays.overlayOpen(settingsLoader) && !overlays.overlayOpen(docsLoader) && !supportHost.popupOpen && !overlays.shell.isEditableFocused()
        onActivated: overlays.openSpotlight("")
    }

    // Ctrl+K for the terminal-jockeys and VS Code muscle-memory victims.
    Shortcut {
        sequence: "Ctrl+K"
        enabled: overlays.shell.visible && !overlays.welcomeOpen && !overlays.shell.fullscreenOpen && !overlays.overlayOpen(spotlightLoader) && !overlays.overlayOpen(settingsLoader) && !overlays.overlayOpen(docsLoader) && !supportHost.popupOpen
        onActivated: overlays.openSpotlight("")
    }

    // Space: because reaching for the mouse to pause when someone walks in is high-stakes cardio.
    Shortcut {
        sequence: "Space"
        autoRepeat: false
        enabled: overlays.shell.visible && !overlays.welcomeOpen && !overlays.overlayOpen(spotlightLoader) && !overlays.overlayOpen(settingsLoader) && !overlays.overlayOpen(docsLoader) && !supportHost.popupOpen && !overlays.shell.isEditableFocused()
        onActivated: OrchardPlayback.toggle()
    }

    // F for fullscreen. Also what you pay respects to when the bridge hits.
    Shortcut {
        sequence: "F"
        autoRepeat: false
        enabled: overlays.shell.visible && !overlays.welcomeOpen && !OrchardMusicVideo.open && Boolean(OrchardPlayback.track.id) && !overlays.overlayOpen(spotlightLoader) && !overlays.overlayOpen(settingsLoader) && !overlays.overlayOpen(docsLoader) && !supportHost.popupOpen && !overlays.shell.isEditableFocused()
        onActivated: overlays.shell.setFullscreen(!overlays.shell.fullscreenOpen)
    }

    Shortcut {
        sequence: "Escape"
        enabled: overlays.shell.fullscreenOpen && !OrchardMusicVideo.open && !overlays.overlayOpen(settingsLoader) && !overlays.overlayOpen(docsLoader) && !supportHost.popupOpen
        onActivated: overlays.shell.setFullscreen(false)
    }

    Shortcut {
        sequence: "Left"
        enabled: overlays.shell.visible && !overlays.welcomeOpen && !overlays.overlayOpen(spotlightLoader) && !overlays.overlayOpen(settingsLoader) && !overlays.overlayOpen(docsLoader) && !supportHost.popupOpen && !overlays.shell.isEditableFocused()
        onActivated: OrchardPlayback.seekBy(-5)
    }

    Shortcut {
        sequence: "Right"
        enabled: overlays.shell.visible && !overlays.welcomeOpen && !overlays.overlayOpen(spotlightLoader) && !overlays.overlayOpen(settingsLoader) && !overlays.overlayOpen(docsLoader) && !supportHost.popupOpen && !overlays.shell.isEditableFocused()
        onActivated: OrchardPlayback.seekBy(5)
    }
}
