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

// Report list on the left, composer or report timeline on the right.
// Signed out or without GitHub, a gate explains what is missing instead.
Item {
    id: root

    property string initialReport: ""
    // "new" shows the composer; "report" shows OrchardSupport.activeReport.
    property string mode: "new"

    signal closeRequested
    signal captureRequested

    readonly property bool ready: OrchardAccount.isSignedIn && OrchardSupport.githubLinked

    // Untyped on purpose: typed map reads in bindings crash qmlcachegen output.
    function activeId() {
        const report = OrchardSupport.activeReport;
        return report && report.id ? report.id : "";
    }

    function showReport(id) {
        root.mode = "report";
        OrchardSupport.openReport(id);
    }

    function showComposer() {
        root.mode = "new";
        OrchardSupport.closeReport();
    }

    Component.onCompleted: {
        if (root.initialReport)
            root.showReport(root.initialReport);
        else
            OrchardSupport.closeReport();
    }

    Connections {
        target: OrchardSupport
        function onSubmitted(url) {
            root.mode = "report";
        }
    }

    SupportGate {
        anchors.fill: parent
        visible: !root.ready
        onCloseRequested: root.closeRequested()
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0
        visible: root.ready

        SupportReportList {
            Layout.preferredWidth: 300
            Layout.fillHeight: true
            currentId: root.mode === "report" ? root.activeId() : ""
            composing: root.mode === "new"
            onReportSelected: id => root.showReport(id)
            onComposeRequested: root.showComposer()
        }

        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: 1
            color: "#1cffffff"
        }

        Loader {
            Layout.fillWidth: true
            Layout.fillHeight: true
            sourceComponent: root.mode === "new" ? composer : timeline
        }
    }

    Component {
        id: composer
        SupportComposer {
            onCloseRequested: root.closeRequested()
            onCaptureRequested: root.captureRequested()
        }
    }

    Component {
        id: timeline
        SupportTimeline {
            onCloseRequested: root.closeRequested()
        }
    }
}
