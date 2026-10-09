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
import "../home"
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Now playing card: cover, title and progress on a flat surface.
Rectangle {
    id: hero
    required property var panel

    Layout.fillWidth: true
    Layout.preferredHeight: 132
    radius: 16
    color: panel.tint(panel.accentColor, 0.08)

    RowLayout {
        anchors.fill: parent
        anchors.margins: 14
        anchors.bottomMargin: 18
        spacing: 14

        TransitionArtwork {
            id: heroArt
            Layout.preferredWidth: 96
            Layout.preferredHeight: 96
            radius: 12
            source: OrchardPlayback.track.thumbnail || ""
            incomingSource: OrchardPlayback.transitionTrack.thumbnail || ""
            transitioning: OrchardPlayback.crossfadeActive
            progress: OrchardPlayback.crossfadeProgress

            // Motion cover plays over the still one and bows out early in a crossfade.
            Loader {
                anchors.fill: parent
                active: OrchardAppearance.animatedArtworkEnabled
                        && Boolean(OrchardPlayback.animatedArtworkUrl)
                        && panel.visible && panel.motionArtwork
                opacity: OrchardPlayback.crossfadeActive ? 1 - Math.min(1, OrchardPlayback.crossfadeProgress / 0.15) : 1

                sourceComponent: Component {
                    AnimatedArtwork {
                        radius: heroArt.radius
                        source: OrchardPlayback.animatedArtworkUrl
                        playing: OrchardPlayback.playing
                    }
                }
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 3

            RowLayout {
                spacing: 6

                // Tiny equalizer. It dances whether or not the song deserves it.
                Item {
                    Layout.preferredWidth: 12
                    Layout.preferredHeight: 10
                    Repeater {
                        model: 3
                        Rectangle {
                            id: bar
                            required property int index
                            x: index * 4.5
                            y: 10 - height
                            width: 2.5
                            radius: 1
                            color: panel.accentColor
                            height: 3
                            SequentialAnimation on height {
                                running: OrchardPlayback.playing && panel.visible
                                loops: Animation.Infinite
                                onRunningChanged: if (!running) bar.height = 3
                                NumberAnimation { to: 10; duration: 260 + bar.index * 90; easing.type: Easing.InOutSine }
                                NumberAnimation { to: 3 + bar.index; duration: 220 + bar.index * 70; easing.type: Easing.InOutSine }
                            }
                        }
                    }
                }

                Text {
                    text: OrchardPlayback.crossfadeActive ? qsTr("MIXING")
                          : OrchardPlayback.playing ? qsTr("NOW PLAYING") : qsTr("PAUSED")
                    color: panel.accentColor
                    font.family: "Inter"
                    font.pixelSize: 10
                    font.bold: true
                    font.letterSpacing: 0.9
                }
            }

            Text {
                objectName: "queueCurrentTitle"
                Layout.fillWidth: true
                text: OrchardPlayback.track.title || qsTr("Nothing playing")
                color: panel.primaryText
                font.family: "Inter"
                font.pixelSize: 15
                font.bold: true
                font.letterSpacing: -0.2
                elide: Text.ElideRight
            }

            Text {
                Layout.fillWidth: true
                text: OrchardPlayback.track.artist || (OrchardPlayback.track.artists || []).join(", ") || ""
                visible: text.length > 0
                color: panel.secondaryText
                font.family: "Inter"
                font.pixelSize: 12
                elide: Text.ElideRight
            }

            Text {
                Layout.fillWidth: true
                objectName: "queueTransitionLabel"
                readonly property string mixStyle: OrchardPlayback.adaptiveMix.mixStyle
                readonly property int mixBpm: Math.round(OrchardPlayback.adaptiveMix.mixBpm)
                readonly property string incomingTitle: OrchardPlayback.transitionTrack.title || ""
                visible: OrchardPlayback.crossfadeActive && incomingTitle.length > 0
                // Name the move while it happens. Credit where credit is due, DJ.
                text: mixStyle.length === 0 ? qsTr("into %1").arg(incomingTitle)
                    : mixBpm > 0 ? qsTr("%1 at %2 BPM into %3").arg(mixStyle).arg(mixBpm).arg(incomingTitle)
                    : qsTr("%1 into %2").arg(mixStyle).arg(incomingTitle)
                color: panel.mutedText
                font.family: "Inter"
                font.pixelSize: 11
                font.italic: true
                elide: Text.ElideRight
            }
        }
    }

    // Progress rail inset along the bottom of the card.
    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.leftMargin: 14
        anchors.rightMargin: 14
        anchors.bottomMargin: 10
        height: 3
        radius: 1.5
        color: "#1fffffff"

        Rectangle {
            height: parent.height
            radius: parent.radius
            color: panel.accentColor
            width: OrchardPlayback.displayDuration > 0
                   ? parent.width * Math.max(0, Math.min(1, OrchardPlayback.displayPosition / OrchardPlayback.displayDuration))
                   : 0
        }
    }
}
