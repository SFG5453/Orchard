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
    // Lit words pull wordClock from this source without ticking each row.
    property var clockSource: null
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
        readonly property real progress: {
            if (!lit)
                return 0;
            const clock = wordRows.clockSource ? wordRows.clockSource.wordClock : wordRows.clock;
            return clock >= end ? 1
                               : Math.max(0, Math.min(1, (clock - start) / Math.max(0.1, end - start)));
        }
        readonly property bool singing: lit && progress > 0 && progress < 1
        // Unclamped position in the word; runs past 1 while the last letters settle.
        readonly property real raw: {
            if (!lit)
                return 0;
            const clock = wordRows.clockSource ? wordRows.clockSource.wordClock : wordRows.clock;
            return (clock - start) / Math.max(0.1, end - start);
        }
        // Splitting joins and combining marks breaks shaping, so only plain Latin text lifts per letter.
        readonly property bool splittable: /^[\u0020-\u024F\u2019]+$/.test(text)
        readonly property int count: text.length
        // Share of the word that one lifting wave covers.
        readonly property real span: Math.max(0.45, 4 / Math.max(1, count))
        readonly property bool lettered: splittable && lit && raw > 0 && raw < 1 + span
        readonly property real feather: Math.min(0.5, pixelSize * 0.38 / Math.max(1, base.implicitWidth))

        implicitWidth: base.implicitWidth
        implicitHeight: base.implicitHeight

        function mix(a, b, t) {
            return Qt.rgba(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t, a.a + (b.a - a.a) * t);
        }

        Text {
            id: base
            text: word.text
            color: word.lit && word.progress >= 1 ? wordRows.doneColor : word.baseColor
            // Letters take over while the word is sung.
            opacity: word.lettered ? 0 : 1
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
                // One letter at a time rises as the fill reaches it, then settles.
                Loader {
                    active: word.lettered
                    sourceComponent: Item {
                        Repeater {
                            model: word.lettered ? word.count : 0
                            Text {
                                required property int index
                                readonly property real from: index / word.count
                                readonly property real local: Math.max(0, Math.min(1, (word.raw - from) / word.span))
                                x: wordRows.metrics.advanceWidth(word.text.slice(0, index))
                                y: -word.pixelSize * 0.018 * Math.sin(Math.PI * local)
                                text: word.text[index]
                                color: word.raw >= 1 ? wordRows.doneColor
                                       : word.mix(word.baseColor, word.fillColor, Math.max(0, Math.min(1, (word.raw - from) * word.count / 1.5)))
                                font: base.font
                            }
                        }
                    }
                }

                // Solid fill up to the playhead, then two fading slivers stand in for a soft gradient edge.
                // Stays up once the word finishes and eases to white, so the hand-off to the base text never flashes dim.
                Sliver {
                    source: base
                    color: word.progress >= 1 ? wordRows.doneColor : word.fillColor
                    visible: word.progress > 0 && !word.lettered
                    from: 0
                    span: word.progress
                    Behavior on color { ColorAnimation { duration: 240 } }
                }
                Sliver { source: base; color: word.fillColor; visible: word.singing && !word.lettered; from: word.progress; span: word.feather / 2; opacity: 0.55 }
                Sliver { source: base; color: word.fillColor; visible: word.singing && !word.lettered; from: word.progress + word.feather / 2; span: word.feather / 2; opacity: 0.22 }
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
