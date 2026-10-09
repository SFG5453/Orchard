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

import ".."
import Orchard
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Previous, play, next and the shuffle and repeat toggles.
RowLayout {
    // The player bar this lives in; it owns the colours and helpers.
    required property Item bar

    Layout.alignment: Qt.AlignVCenter
    spacing: bar.canopy || bar.compact ? 4 : 8

    Repeater {
        model: bar.compact ? ["skip-back", "play", "skip-forward"] : ["shuffle", "skip-back", "play", "skip-forward", "repeat"]

        Button {
            id: transportButton

            required property string modelData
            readonly property bool modeButton: modelData === "shuffle" || modelData === "repeat"
            readonly property bool modeActive: modelData === "shuffle" ? OrchardPlayback.shuffleEnabled : modelData === "repeat" && OrchardPlayback.repeatMode !== "off"
            readonly property bool mainButton: modelData === "play"
            readonly property string iconName: {
                if (mainButton)
                    return OrchardPlayback.playing ? "pause" : "play";

                if (modelData === "repeat" && OrchardPlayback.repeatMode === "one")
                    return "repeat-1";
                return modelData;
            }
            readonly property string tooltipText: {
                if (modelData === "shuffle")
                    return OrchardPlayback.shuffleEnabled ? qsTr("Shuffle on") : qsTr("Shuffle off");
                if (modelData === "repeat")
                    return OrchardPlayback.repeatMode === "one" ? qsTr("Repeat one") : OrchardPlayback.repeatMode === "all" ? qsTr("Repeat all") : qsTr("Repeat off");
                if (modelData === "skip-back")
                    return qsTr("Previous");

                if (modelData === "skip-forward")
                    return qsTr("Next");

                return OrchardPlayback.playing ? qsTr("Pause") : qsTr("Play");
            }

            enabled: {
                if (modeButton) return true;
                if (OrchardPlayback.loading)
                    return false;

                if (mainButton)
                    return Boolean(OrchardPlayback.track.id);

                if (modelData === "skip-back")
                    return OrchardPlayback.canGoPrevious;

                if (modelData === "skip-forward")
                    return OrchardPlayback.canGoNext;

                return false;
            }
            implicitWidth: mainButton ? bar.controlSize + 8 : bar.controlSize
            implicitHeight: implicitWidth
            hoverEnabled: true
            focusPolicy: Qt.TabFocus
            onClicked: {
                switch (modelData) {
                case "shuffle":
                    OrchardPlayback.shuffleEnabled = !OrchardPlayback.shuffleEnabled;
                    break;
                case "repeat":
                    OrchardPlayback.cycleRepeatMode();
                    break;
                case "play":
                    OrchardPlayback.toggle();
                    break;
                case "skip-back":
                    OrchardPlayback.previous();
                    break;
                case "skip-forward":
                    OrchardPlayback.next();
                    break;
                }
            }
            Accessible.name: tooltipText
            Accessible.checkable: modeButton
            Accessible.checked: modeActive
            ToolTip.visible: hovered
            ToolTip.delay: 500
            ToolTip.text: tooltipText

            background: Rectangle {
                radius: width / 2
                border.color: transportButton.activeFocus ? bar.primaryText : "transparent"
                color: {
                    if (transportButton.mainButton) {
                        if (transportButton.down)
                            return Qt.darker(bar.accentColor, 1.15);
                        if (transportButton.hovered)
                            return Qt.lighter(bar.accentColor, 1.08);
                        return bar.accentColor;
                    }
                    if (transportButton.modeActive)
                        return bar.tint(bar.accentColor, transportButton.hovered ? 0.3 : 0.2);
                    if (transportButton.hovered && transportButton.enabled)
                        return "#1fffffff";
                    return "transparent";
                }
                opacity: transportButton.enabled ? 1 : 0.4
                scale: transportButton.mainButton && transportButton.down ? 0.94 : 1

                Behavior on color { ColorAnimation { duration: 90 } }
                Behavior on scale { NumberAnimation { duration: 90; easing.type: Easing.OutCubic } }
            }

            contentItem: LucideIcon {
                name: transportButton.iconName
                color: {
                    if (transportButton.mainButton)
                        return bar.onAccentColor;

                    if (!transportButton.enabled)
                        return "#6b6f68";

                    if (transportButton.modeActive)
                        return bar.accentColor;

                    return bar.controlColor;
                }
                implicitWidth: transportButton.mainButton ? 20 : 17
                implicitHeight: implicitWidth
            }
        }
    }
}
