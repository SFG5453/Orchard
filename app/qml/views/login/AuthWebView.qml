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
import QtQuick.Controls
import QtWebView

Item {
    id: root

    signal closeRequested
    signal loginSucceeded

    anchors.fill: parent

    readonly property string extractScript: `
        (function() {
            try {
                var config = window.ytcfg;
                var get = config && typeof config.get === 'function' ? function(key) { return config.get(key); } : function() { return ''; };
                var legacy = window.yt && window.yt.config_ ? window.yt.config_ : {};
                var findScriptValue = function(key) {
                    for (var i = 0; i < document.scripts.length; i++) {
                        var text = document.scripts[i].textContent || '';
                        var match = text.match(new RegExp('"' + key + '":"([^"]+)"'));
                        if (match) return match[1];
                    }
                    return '';
                };
                var delegatedSessionId = get('DELEGATED_SESSION_ID') || legacy.DELEGATED_SESSION_ID || findScriptValue('DELEGATED_SESSION_ID') || '';
                // The account menu can take a coffee break; the nav avatar is already here.
                var avatar = document.querySelector('#avatar-btn img[src], ytmusic-settings-button button img[src], ytmusic-nav-bar #avatar img[src], ytmusic-nav-bar img[alt*="avatar" i]');
                var avatarUrl = avatar ? (avatar.currentSrc || avatar.src || '') : '';
                return JSON.stringify({
                    origin: location.origin,
                    visitorData: get('VISITOR_DATA') || legacy.VISITOR_DATA || findScriptValue('VISITOR_DATA') || '',
                    delegatedSessionId: delegatedSessionId,
                    dataSyncId: get('DATASYNC_ID') || legacy.DATASYNC_ID || findScriptValue('DATASYNC_ID') || '',
                    accountIndex: get('SESSION_INDEX') ?? legacy.SESSION_INDEX ?? findScriptValue('SESSION_INDEX'),
                    clientVersion: get('INNERTUBE_CLIENT_VERSION') || legacy.INNERTUBE_CLIENT_VERSION || findScriptValue('INNERTUBE_CLIENT_VERSION') || '',
                    avatar: /^data:/i.test(avatarUrl) ? '' : avatarUrl,
                    cookie: document.cookie || ''
                });
            } catch (error) {
                return JSON.stringify({
                    visitorData: '',
                    delegatedSessionId: '',
                    dataSyncId: '',
                    accountIndex: 0,
                    cookie: document.cookie || ''
                });
            }
        })();
    `

    property int profileProbeAttempts: 0

    readonly property string profileScript: `
        (function() {
            if (location.origin !== 'https://music.youtube.com' && location.origin !== 'https://www.youtube.com') return JSON.stringify({});
            const clean = function(value) { return String(value || '').replace(/\\s+/g, ' ').trim(); };
            const button = document.querySelector('#avatar-btn, ytmusic-settings-button button, button[aria-label*="Account" i], button[aria-label*="profile" i], ytmusic-nav-bar #avatar img, ytmusic-nav-bar img[alt*="avatar" i]');
            if (button && !window.__orchardProfileClicked && !document.querySelector('ytd-active-account-header-renderer, yt-multi-page-menu-header-renderer, ytmusic-account-info')) {
                (button.closest('button, #avatar-btn') || button).click();
                window.__orchardProfileClicked = true;
                return JSON.stringify({});
            }
            const area = document.querySelector('ytd-active-account-header-renderer, yt-multi-page-menu-header-renderer, ytmusic-account-info, tp-yt-iron-dropdown');
            const image = (area && area.querySelector('img[src]')) || (button && button.querySelector('img[src]')) || (button && button.matches('img[src]') ? button : null);
            const imageUrl = image ? (image.currentSrc || image.src || '') : '';
            const link = area ? area.querySelector('a[href*="/channel/UC"], a[href*="/@"]') : null;
            const href = link ? link.href : '';
            const channelId = (href.match(/\\/channel\\/(UC[a-zA-Z0-9_-]{22})/) || [])[1] || '';
            const handleFromUrl = (href.match(/\\/(@[^/?#]+)/) || [])[1] || '';
            const lines = area ? String(area.innerText || '').split('\\n').map(clean).filter(Boolean) : [];
            const ignored = /^(account|account menu|manage your google account|switch account|sign out|youtube|youtube music)$/i;
            const handle = lines.find(function(line) { return /^@/.test(line); }) || handleFromUrl;
            const name = lines.find(function(line) {
                return line !== handle && !ignored.test(line) && !/subscribers?|privacy|terms|add account/i.test(line) && !/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(line);
            }) || '';
            return JSON.stringify({
                name: name,
                handle: handle,
                avatarUrl: /^data:/i.test(imageUrl) ? '' : imageUrl,
                channelId: channelId,
                channelUrl: href
            });
        })();
    `

    property bool authCheckPending: false
    // Switch mode: the chooser has shown, so the next YouTube page is the pick.
    property bool switcherSeen: false
    // Identity the chooser opened with. /channel_switcher redirects to /account,
    // so a capture only counts once the page reports a different account.
    property string switchBaseline: ""

    function switchIdentity(result) {
        try {
            const page = JSON.parse(result);
            if (!page.dataSyncId && !page.delegatedSessionId)
                return "";
            return [page.dataSyncId, page.delegatedSessionId, page.accountIndex].join("|");
        } catch (_) {
            return "";
        }
    }

    function captureAllowed(urlStr) {
        if (!OrchardAuth.switchMode)
            return /^https:\/\/music\.youtube\.com(?:\/|$)/.test(urlStr);
        // any YouTube page after the chooser, except the redirects on the way.
        return root.switcherSeen
               && /^https:\/\/(?:www\.|m\.|music\.)?youtube\.com(?:\/|$)/.test(urlStr)
               && !/\/(?:channel_switcher|signin)(?:[/?#]|$)/.test(urlStr);
    }

    function checkAuth() {
        const urlStr = webView.url.toString();
        if (/^https:\/\/(?:www\.)?youtube\.com\/channel_switcher/.test(urlStr))
            root.switcherSeen = true;
        if (authCheckPending || OrchardAuth.profileLoading || !OrchardAuth.authWebViewActive)
            return;
        if (root.captureAllowed(urlStr) && !webView.loading) {
            authCheckPending = true;
            webView.runJavaScript(extractScript, function (result) {
                root.authCheckPending = false;
                if (result && typeof result === "string" && result.length > 2) {
                    if (OrchardAuth.switchMode) {
                        const identity = root.switchIdentity(result);
                        if (!identity)
                            return;
                        if (!root.switchBaseline)
                            root.switchBaseline = identity;
                        // Same chair as before the pick. Nobody has moved yet.
                        if (identity === root.switchBaseline)
                            return;
                    }
                    const parsed = OrchardAuth.handlePageAuth(result);
                    if (parsed) {
                        root.loginSucceeded();
                    }
                }
            });
        }
    }

    // Page config and the native cookie store need not be ready at the load
    // event. Retry the capture while sign-in is active, before verification.
    Timer {
        interval: 300
        repeat: true
        running: OrchardAuth.authWebViewActive && !OrchardAuth.profileLoading
        onTriggered: root.checkAuth()
    }

    property string accountToken: ""
    property bool accountPollPending: false

    function requestAccount(token, authorization) {
        accountToken = token;
        const script = `(function() {
            if (location.origin !== 'https://music.youtube.com') return;
            const token = ${JSON.stringify(token)};
            window.__orchardAccount = { token: token, pending: true };
            const get = function(key) { return window.ytcfg && window.ytcfg.get ? window.ytcfg.get(key) : undefined; };
            const pageContext = get('INNERTUBE_CONTEXT') || {};
            const pageClient = pageContext.client || {};
            const delegatedId = get('DELEGATED_SESSION_ID') || '';
            const context = { client: {
                clientName: 'WEB_REMIX',
                clientVersion: get('INNERTUBE_CLIENT_VERSION') || pageClient.clientVersion,
                hl: pageClient.hl || 'en',
                gl: pageClient.gl || 'US',
                visitorData: get('VISITOR_DATA') || pageClient.visitorData || undefined
            }};
            if (delegatedId) context.user = { onBehalfOfUser: String(delegatedId) };
            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/youtubei/v1/account/accounts_list?prettyPrint=false&alt=json');
            xhr.withCredentials = true;
            xhr.timeout = 15000;
            xhr.setRequestHeader('Content-Type', 'application/json');
            xhr.setRequestHeader('Authorization', ${JSON.stringify(authorization)});
            xhr.setRequestHeader('X-Goog-AuthUser', String(get('SESSION_INDEX') || 0));
            xhr.setRequestHeader('X-Origin', location.origin);
            xhr.setRequestHeader('X-YouTube-Client-Name', '67');
            if (context.client && context.client.clientVersion)
                xhr.setRequestHeader('X-YouTube-Client-Version', context.client.clientVersion);
            xhr.setRequestHeader('X-Youtube-Bootstrap-Logged-In', 'true');
            if (context.client.visitorData)
                xhr.setRequestHeader('X-Goog-Visitor-Id', context.client.visitorData);
            if (delegatedId)
                xhr.setRequestHeader('X-Goog-PageId', String(delegatedId));
            const complete = function(failure) {
                if (!window.__orchardAccount || window.__orchardAccount.token !== token) return;
                let data = null;
                let body = xhr.responseText || '';
                // Some Google responses include an anti-XSSI prefix before JSON.
                if (body.startsWith(")]}'")) body = body.substring(4).trimStart();
                try { data = JSON.parse(body); } catch (_) {}
                window.__orchardAccount = { token: token, status: xhr.status, data: data,
                    failure: failure || '' };
            };
            xhr.onload = function() { complete(''); };
            xhr.onerror = function() { complete('network'); };
            xhr.ontimeout = function() { complete('timeout'); };
            // Match account.getInfo(true): delegated identities require the
            // channel-switcher request rather than the default TV account query.
            xhr.send(JSON.stringify({
                context: context,
                requestType: 'ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER',
                callCircumstance: 'SWITCHING_USERS_FULL'
            }));
        })();`;

        webView.runJavaScript(script);
        accountPollTimer.start();
    }

    Timer {
        id: accountPollTimer
        interval: 200
        repeat: true
        onTriggered: {
            if (root.accountPollPending)
                return;
            root.accountPollPending = true;
            const token = root.accountToken;
            webView.runJavaScript("JSON.stringify(window.__orchardAccount || null)", function (result) {
                root.accountPollPending = false;
                if (token !== root.accountToken)
                    return;
                try {
                    const response = JSON.parse(result);
                    if (!response || response.pending || response.token !== token)
                        return;
                    accountPollTimer.stop();
                    OrchardAuth.submitPageAccount(token, result);
                } catch (_) {}
            });
        }
    }

    function probeAccountProfile() {
        profileProbeAttempts = 0;
        webView.runJavaScript("window.__orchardProfileClicked = false");
        profileProbeTimer.start();
    }

    Connections {
        target: OrchardAuth
        function onPageAccountRequested(token, authorization) {
            root.requestAccount(token, authorization);
        }
        function onProfileProbeRequested() {
            root.probeAccountProfile();
        }
    }

    Timer {
        id: profileProbeTimer
        interval: 200
        repeat: true
        onTriggered: {
            root.profileProbeAttempts += 1;
            webView.runJavaScript(root.profileScript, function (result) {
                if (!result || typeof result !== "string")
                    return;
                try {
                    const profile = JSON.parse(result);
                    if (profile.name || profile.handle || profile.avatarUrl || profile.channelId || profile.channelUrl)
                        OrchardAuth.submitPageProfile(result);
                    if (profile.name && profile.handle && profile.avatarUrl)
                        profileProbeTimer.stop();
                } catch (_) {}
            });
            if (root.profileProbeAttempts >= 40)
                profileProbeTimer.stop();
        }
    }

    Column {
        anchors.fill: parent

        // Top Navigation Toolbar
        Rectangle {
            id: navBar
            width: parent.width
            height: 48
            color: "#16191e"
            border.color: "#252a33"
            border.width: 1

            Row {
                anchors.left: parent.left
                anchors.leftMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                spacing: 8

                // Back button
                Rectangle {
                    width: 32
                    height: 32
                    radius: 6
                    color: backHover.containsMouse ? "#242932" : "transparent"

                    Text {
                        anchors.centerIn: parent
                        text: "←"
                        color: webView.canGoBack ? "#e5e7eb" : "#4b5563"
                        font.pixelSize: 16
                    }

                    MouseArea {
                        id: backHover
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: webView.canGoBack ? Qt.PointingHandCursor : Qt.ArrowCursor
                        onClicked: {
                            if (webView.canGoBack) {
                                webView.goBack();
                            } else {
                                root.closeRequested();
                            }
                        }
                    }
                }

                // Reload button
                Rectangle {
                    width: 32
                    height: 32
                    radius: 6
                    color: reloadHover.containsMouse ? "#242932" : "transparent"

                    Text {
                        anchors.centerIn: parent
                        text: "↻"
                        color: "#e5e7eb"
                        font.pixelSize: 16
                    }

                    MouseArea {
                        id: reloadHover
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: webView.reload()
                    }
                }

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: webView.title ? webView.title
                                        : OrchardAuth.switchMode ? qsTr("Choose a YouTube account")
                                                                 : qsTr("Sign in to YouTube Music")
                    color: "#9ca3af"
                    font.pixelSize: 13
                    font.family: "Inter"
                    elide: Text.ElideRight
                    width: Math.min(300, root.width - 200)
                }
            }

            // Close button (top right of toolbar)
            Rectangle {
                anchors.right: parent.right
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                width: 32
                height: 32
                radius: 6
                color: closeHover.containsMouse ? "#242932" : "transparent"

                Text {
                    anchors.centerIn: parent
                    text: "✕"
                    color: "#9ca3af"
                    font.pixelSize: 13
                }

                MouseArea {
                    id: closeHover
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: root.closeRequested()
                }
            }

            // Load progress bar
            Rectangle {
                anchors.bottom: parent.bottom
                anchors.left: parent.left
                width: parent.width * (webView.loadProgress / 100.0)
                height: 2
                color: "#67d98b"
                visible: webView.loading
            }
        }

        // WebView displaying Google / YouTube sign-in
        WebView {
            id: webView
            width: parent.width
            height: parent.height - navBar.height
            url: OrchardAuth.switchMode ? OrchardAuth.channelSwitcherUrl : OrchardAuth.loginUrl

            onLoadingChanged: function (loadRequest) {
                if (loadRequest.status === WebView.LoadSucceededStatus) {
                    root.checkAuth();
                }
            }

            onUrlChanged: {
                root.checkAuth();
            }
        }
    }
}
