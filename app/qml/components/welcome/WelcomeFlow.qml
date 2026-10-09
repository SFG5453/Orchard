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

// First-run setup flow shown after the first sign-in.
WelcomeFlowForm {
    id: root

    readonly property int lastStep: stack.count - 1

    signal settingsRequested
    signal docsRequested
    signal finished

    function next() {
        if (stack.currentIndex < root.lastStep)
            stack.currentIndex++;
        else
            root.finished();
    }

    focus: true
    Component.onCompleted: root.forceActiveFocus()
    Keys.onEscapePressed: root.finished()

    backButton.visible: stack.currentIndex > 0 && stack.currentIndex < root.lastStep
    backButton.onClicked: stack.currentIndex--
    skipButton.visible: stack.currentIndex < root.lastStep
    skipButton.onClicked: root.finished()
    nextButton.text: {
        if (stack.currentIndex === 0)
            return qsTr("Get started");
        return stack.currentIndex === root.lastStep ? qsTr("Start listening") : qsTr("Continue");
    }
    nextButton.onClicked: root.next()
    stack.onCurrentIndexChanged: pageScroller.contentY = 0

    dashRepeater.model: stack.count
    dashRepeater.delegate: Rectangle {
        id: dash

        required property int index

        width: index === stack.currentIndex ? 44 : 16
        height: 6
        radius: 3
        color: index === stack.currentIndex ? "#ffffff" : (index < stack.currentIndex ? "#8a909a" : "#2f343d")

        Behavior on width {
            NumberAnimation {
                duration: 220
                easing.type: Easing.OutCubic
            }
        }

        Behavior on color {
            ColorAnimation {
                duration: 180
            }
        }
    }

    WelcomeIntroPage {}

    WelcomeAppearancePage {}

    WelcomePlaybackPage {}

    WelcomeLibraryPage {}

    WelcomeConnectionsPage {
        onSettingsRequested: root.settingsRequested()
    }

    WelcomeSystemPage {}

    WelcomeDonePage {
        onSettingsRequested: root.settingsRequested()
        onDocsRequested: root.docsRequested()
    }
}
