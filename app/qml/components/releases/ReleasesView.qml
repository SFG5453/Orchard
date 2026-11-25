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

// Release notes state and layout: release list on the left, the selected release on the right.
// Reads the bundled history from OrchardReleaseNotes and marks what was opened last in OrchardAppearance.
Item {
    id: root

    // Version to open on; falls back to the installed build, then the newest release.
    property string initialRelease: ""
    property string filter: ""
    property string selectedVersion: ""
    property bool copied: false
    // Newest release opened before this view appeared. Releases above it are marked new.
    property string seenVersion: ""

    readonly property string installedVersion: Qt.application.version
    readonly property var entries: root.buildEntries(OrchardReleaseNotes, root.installedVersion, root.seenVersion)
    readonly property var shown: root.entries.filter(entry => root.matches(entry, root.filter))
    readonly property var entry: root.shown.find(item => item.version === root.selectedVersion) || root.shown[0] || null
    readonly property int entryIndex: root.entry ? root.entries.findIndex(item => item.version === root.entry.version) : -1

    signal closeRequested

    function focusSearch() {
        sidebar.focusSearch();
    }

    function sectionStyle(title) {
        const name = String(title).toLowerCase();
        if (/fix|bug/.test(name))
            return { icon: "bug", tone: "#e0b878" };
        if (/remov|deprecat/.test(name))
            return { icon: "trash-2", tone: "#e08f8f" };
        if (/chang|improv|tweak|update/.test(name))
            return { icon: "sliders-horizontal", tone: "#9ab8d8" };
        if (/soon|plan|upcoming|next/.test(name))
            return { icon: "clock", tone: "#a4aaa1" };
        return { icon: "sparkles", tone: OrchardAppearance.accent };
    }

    // Lists from C++ arrive as sequence wrappers, so Array.isArray is false for them.
    function listOf(value) {
        return value && typeof value !== "string" && value.length !== undefined ? Array.from(value) : [];
    }

    function dateLabel(iso) {
        const parts = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso));
        if (!parts)
            return String(iso || "");
        return Qt.formatDate(new Date(+parts[1], +parts[2] - 1, +parts[3]), "MMM d, yyyy");
    }

    // "canary" from 1.0.0-canary.8; empty for stable versions.
    function channelOf(version) {
        const tag = /-([a-z]+)/i.exec(String(version));
        return tag ? tag[1].charAt(0).toUpperCase() + tag[1].slice(1).toLowerCase() : "";
    }

    // The optional announcement of a release; every field is empty without one.
    function calloutOf(value) {
        const callout = value || {};
        return {
            title: String(callout.title || ""),
            text: String(callout.text || ""),
            action: String(callout.action || qsTr("Open")),
            url: String(callout.url || "")
        };
    }

    function markdownOf(version, title, date, callout, sections) {
        let out = "# Orchard " + version + "\n\n" + title + (date ? " (" + date + ")" : "") + "\n";
        if (callout.title !== "")
            out += "\n## " + callout.title + "\n" + callout.text + (callout.url ? "\n\n" + callout.url : "") + "\n";
        for (const section of sections)
            out += "\n## " + section.title + "\n" + section.items.map(line => "- " + line).join("\n") + "\n";
        return out;
    }

    // How many releases at the top of the history the person has not opened yet.
    function unseenCount(releases, seen) {
        if (seen === "")
            return OrchardAppearance.welcomeSeen ? 1 : 0;
        const at = releases.findIndex(release => release.version === seen);
        return at < 0 ? 1 : at;
    }

    function buildEntries(releases, installed, seen) {
        const fresh = root.unseenCount(releases, seen);
        const out = [];
        for (let i = 0; i < releases.length; ++i) {
            const release = releases[i];
            const sections = [];
            const highlights = root.listOf(release.highlights).map(String);
            if (highlights.length > 0)
                sections.push({ title: qsTr("Highlights"), items: highlights });
            for (const section of root.listOf(release.sections)) {
                const items = root.listOf(section.items).map(String);
                if (items.length > 0)
                    sections.push({ title: String(section.title || qsTr("Changes")), items: items });
            }
            let count = 0;
            for (const section of sections) {
                const style = root.sectionStyle(section.title);
                section.icon = style.icon;
                section.tone = style.tone;
                count += section.items.length;
            }
            const version = String(release.version);
            const title = String(release.title || "");
            const date = root.dateLabel(release.date);
            const callout = root.calloutOf(release.callout);
            out.push({
                version: version,
                title: title,
                dateLabel: date,
                meta: (date ? date + " · " : "") + (count === 1 ? qsTr("1 change") : qsTr("%1 changes").arg(count)),
                channel: root.channelOf(version),
                installed: version === installed,
                isNew: i < fresh,
                count: count,
                callout: callout,
                sections: sections,
                markdown: root.markdownOf(version, title, date, callout, sections),
                haystack: (version + "\n" + title + "\n" + callout.title + "\n" + callout.text + "\n" + sections.map(s => s.title + "\n" + s.items.join("\n")).join("\n")).toLowerCase()
            });
        }
        return out;
    }

    // Every typed word must appear somewhere in the release.
    function matches(entry, query) {
        const words = String(query).toLowerCase().split(/\s+/).filter(word => word.length > 0);
        return words.every(word => entry.haystack.indexOf(word) >= 0);
    }

    function select(version) {
        root.selectedVersion = version;
    }

    // Steps through the shown list; -1 is newer, +1 is older.
    function step(delta) {
        const at = root.shown.findIndex(item => root.entry && item.version === root.entry.version);
        const next = root.shown[at + delta];
        if (next)
            root.select(next.version);
    }

    function copy() {
        if (!root.entry)
            return;
        OrchardBackend.copyToClipboard(root.entry.markdown);
        root.copied = true;
        copiedTimer.restart();
    }

    onSelectedVersionChanged: root.copied = false

    Component.onCompleted: {
        root.seenVersion = OrchardAppearance.lastSeenRelease;
        const wanted = [root.initialRelease, root.installedVersion].find(version => root.entries.some(item => item.version === version));
        root.selectedVersion = wanted || (root.entries.length > 0 ? root.entries[0].version : "");
        if (root.entries.length > 0)
            OrchardAppearance.lastSeenRelease = root.entries[0].version;
    }

    Keys.onUpPressed: root.step(-1)
    Keys.onDownPressed: root.step(1)

    Timer {
        id: copiedTimer
        interval: 1600
        onTriggered: root.copied = false
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        ReleasesSidebar {
            id: sidebar
            Layout.preferredWidth: 300
            Layout.fillHeight: true
            entries: root.shown
            currentVersion: root.entry ? root.entry.version : ""
            filter: root.filter
            onSelected: version => root.select(version)
            onFilterEdited: text => root.filter = text
        }

        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: 1
            color: "#1cffffff"
        }

        ReleaseReader {
            Layout.fillWidth: true
            Layout.fillHeight: true
            entry: root.entry
            newer: root.entryIndex > 0 ? root.entries[root.entryIndex - 1] : null
            older: root.entryIndex >= 0 && root.entryIndex < root.entries.length - 1 ? root.entries[root.entryIndex + 1] : null
            copied: root.copied
            onReleaseRequested: version => {
                root.filter = "";
                root.select(version);
            }
            onCopyRequested: root.copy()
            onCloseRequested: root.closeRequested()
        }
    }
}
