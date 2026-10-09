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

// Word-timed lyric text wrapped into rows. Timed words wipe individually,
// so wrapping never smears the fill across a line break.
Column {
    id: wordRows

    property var words: []
    property var ends: []
    property bool lit: false
    property FontMetrics metrics
    property real gap
    property int pixelSize
    property int weight
    property bool alignRight
    property color baseColor
    property color fillColor
    // Colour a finished word settles on.
    property color doneColor
    property real clock
    readonly property var rows: wrapWords(words, metrics, gap, width - 2)

    spacing: Math.round(pixelSize * 0.24)

    // Greedy wrap into rows of word indices. Flow can't right-align rows without reversing them.
    function wrapWords(list, metrics, gap, maxWidth) {
        const rows = [];
        let row = [];
        let width = 0;
        for (let i = 0; i < list.length; ++i) {
            const advance = metrics.advanceWidth(String(list[i].text || "").trim());
            if (row.length && width + gap + advance > maxWidth) {
                rows.push(row);
                row = [];
                width = 0;
            }
            width += (row.length ? gap : 0) + advance;
            row.push(i);
        }
        if (row.length)
            rows.push(row);
        return rows;
    }

    // Karaoke fill for one word. Past words go solid; only the sung word renders the wipe.
    component WordFill: Item {
        id: word
        property string text
        property real start
        property real end
        property int pixelSize: wordRows.pixelSize
        property int weight: Font.Bold
        property bool lit: false
        property color baseColor
        property color fillColor
        readonly property real progress: !lit ? 0
                                         : wordRows.clock >= end ? 1
                                         : Math.max(0, Math.min(1, (wordRows.clock - start) / Math.max(0.1, end - start)))
        readonly property bool singing: lit && progress > 0 && progress < 1
        readonly property real feather: Math.min(0.5, pixelSize * 0.38 / Math.max(1, base.implicitWidth))

        implicitWidth: base.implicitWidth
        implicitHeight: base.implicitHeight

        Text {
            id: base
            text: word.text
            color: word.lit && word.progress >= 1 ? wordRows.doneColor : word.baseColor
            font.family: "Inter"
            font.pixelSize: word.pixelSize
            font.weight: word.weight
            font.letterSpacing: -0.4
            Behavior on color { ColorAnimation { duration: 180 } }
        }

        // Built only for the lit line; slivers on every word tripled the cost of loading a song.
        Loader {
            active: word.lit
            sourceComponent: Item {
                // Solid fill up to the playhead, then two fading slivers stand in for a soft gradient edge.
                // Stays up once the word finishes and eases to white, so the hand-off to the base text never flashes dim.
                Sliver {
                    source: base
                    color: word.progress >= 1 ? wordRows.doneColor : word.fillColor
                    visible: word.progress > 0
                    from: 0
                    span: word.progress
                    Behavior on color { ColorAnimation { duration: 240 } }
                }
                Sliver { source: base; color: word.fillColor; visible: word.singing; from: word.progress; span: word.feather / 2; opacity: 0.55 }
                Sliver { source: base; color: word.fillColor; visible: word.singing; from: word.progress + word.feather / 2; span: word.feather / 2; opacity: 0.22 }
            }
        }
    }

    // Horizontal slice of a word, recoloured. WordFill stacks these for the wipe.
    component Sliver: Item {
        id: sliver
        property Text source
        property color color
        property real from
        property real span
        x: source.implicitWidth * from
        width: Math.max(0, Math.min(source.implicitWidth - x, source.implicitWidth * span))
        height: source.implicitHeight
        clip: true

        Text {
            x: -sliver.x
            text: sliver.source.text
            color: sliver.color
            font: sliver.source.font
        }
    }

    Repeater {
        model: wordRows.rows
        Row {
            id: wordRow
            required property var modelData
            x: wordRows.alignRight ? wordRows.width - implicitWidth : 0
            spacing: wordRows.gap

            Repeater {
                model: wordRow.modelData
                WordFill {
                    required property int modelData
                    readonly property var entry: wordRows.words[modelData]
                    text: String(entry.text || "").trim()
                    start: Number(entry.startTime || 0)
                    end: wordRows.ends[modelData] || start + 0.4
                    pixelSize: wordRows.pixelSize
                    weight: wordRows.weight
                    lit: wordRows.lit
                    baseColor: wordRows.baseColor
                    fillColor: wordRows.fillColor
                }
            }
        }
    }
}
