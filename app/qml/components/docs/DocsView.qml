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

pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Layouts

// Docs state and layout: sidebar on the left, reader on the right.
// The only docs file that talks to OrchardDocs, OrchardDocsAssistant and OrchardBackend.
Item {
    id: root

    // Page to show when the view opens; falls back to the first page.
    property string initialPage: ""
    property string pageId: ""
    property string filter: ""
    // Section of pageId to scroll to; focusSerial counts requests so a repeat still scrolls.
    property string focusSection: ""
    property int focusSerial: 0
    // "page" or "all" while the matching copy button shows its confirmation.
    property string copied: ""

    signal closeRequested

    readonly property var allPages: OrchardDocs.pages
    readonly property var shownPages: {
        if (root.filter.trim().length === 0)
            return root.allPages;
        const ids = OrchardDocs.search(root.filter);
        return root.allPages.filter(entry => ids.includes(entry.id));
    }
    readonly property int pageIndex: root.allPages.findIndex(entry => entry.id === root.pageId)

    function focusSearch() {
        sidebar.focusSearch();
    }

    function openPage(id) {
        root.focusSection = "";
        root.pageId = id;
    }

    function openAnswer(answer) {
        root.focusSection = answer.section;
        root.pageId = answer.pageId;
        root.focusSerial += 1;
    }

    function edit(text) {
        root.filter = text;
        OrchardDocsAssistant.ask(text);
    }

    function copy(kind) {
        OrchardBackend.copyToClipboard(kind === "all" ? OrchardDocs.bundle() : OrchardDocs.rawPage(root.pageId));
        root.copied = kind;
        copiedTimer.restart();
    }

    Component.onCompleted: {
        const known = root.allPages.some(entry => entry.id === root.initialPage);
        root.pageId = known ? root.initialPage : (root.allPages.length > 0 ? root.allPages[0].id : "");
        OrchardDocsAssistant.ask("");
        OrchardDocsAssistant.prepare();
    }

    Timer {
        id: copiedTimer
        interval: 1600
        onTriggered: root.copied = ""
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        DocsSidebar {
            id: sidebar
            Layout.preferredWidth: 292
            Layout.fillHeight: true
            pages: root.shownPages
            answers: OrchardDocsAssistant.answers
            asking: OrchardDocsAssistant.busy
            askable: OrchardDocsAssistant.available
            currentId: root.pageId
            filter: root.filter
            copiedAll: root.copied === "all"
            onPageSelected: id => root.openPage(id)
            onAnswerSelected: answer => root.openAnswer(answer)
            onFilterEdited: text => root.edit(text)
            onCopyAllRequested: root.copy("all")
        }

        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: 1
            color: "#1cffffff"
        }

        DocsReader {
            Layout.fillWidth: true
            Layout.fillHeight: true
            page: root.pageIndex >= 0 ? root.allPages[root.pageIndex] : null
            sections: OrchardDocs.sections(root.pageId)
            related: OrchardDocs.related(root.pageId)
            previousPage: root.pageIndex > 0 ? root.allPages[root.pageIndex - 1] : null
            nextPage: root.pageIndex >= 0 && root.pageIndex < root.allPages.length - 1 ? root.allPages[root.pageIndex + 1] : null
            focusSection: root.focusSection
            focusSerial: root.focusSerial
            copied: root.copied === "page"
            onPageRequested: id => root.openPage(id)
            onCopyRequested: root.copy("page")
            onCloseRequested: root.closeRequested()
        }
    }
}
