// SPDX-License-Identifier: AGPL-3.0-or-later
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
    '../app/qml/components/player/FullscreenLayout.js'), 'utf8');
const layout = vm.runInNewContext(source.replace(/^\.pragma library\s*$/m, '')
    + '\n({ columns, measure, transport })');

function inside(rect, width, height, label) {
    for (const value of Object.values(rect)) assert.ok(Number.isFinite(value), label);
    assert.ok(rect.width >= 0 && rect.height >= 0, label);
    assert.ok(rect.x >= -0.001 && rect.y >= -0.001, label);
    assert.ok(rect.x + rect.width <= width + 0.001, `${label}: right edge`);
    assert.ok(rect.y + rect.height <= height + 0.001, `${label}: bottom edge`);
}

test('fullscreen artwork, controls and panes fit extreme desktop resolutions', () => {
    for (const width of [240, 320, 480, 720, 900, 979, 980, 1100, 1366, 1920, 3840]) {
        for (const height of [240, 320, 480, 600, 1080, 1920]) {
            for (const controlsHeight of [224, 250, 320]) {
                for (const pane of ['', 'lyrics', 'queue']) {
                    for (const shape of [0, 0.5, 1, 1.12]) {
                        for (const [aspect, idle] of [[0.5, 0], [9 / 16, 0], [1, 0], [0.5, 1], [9 / 16, 0.5], [1, 1]]) {
                            const split = width >= 980 && pane ? 1 : 0;
                            const focus = pane === 'lyrics' ? idle : 0;
                            const column = layout.columns(width, split, focus);
                            const result = layout.measure(width, height, controlsHeight,
                                split, pane, shape, aspect, focus, idle);
                            const label = `${width}x${height}, ${controlsHeight}, ${pane}, ${shape}, ${aspect}, ${idle}`;
                            inside({ x: column.x + (column.width - result.artWidth) / 2,
                                y: result.columnY, width: result.artWidth,
                                height: result.artHeight }, width, height, `art: ${label}`);
                            const controlBottom = result.controlsY + controlsHeight * result.controlsScale;
                            inside({ x: result.controlsX, y: result.controlsY,
                                width: column.width * result.controlsScale,
                                height: controlsHeight * result.controlsScale }, width, height, `controls: ${label}`);
                            if (pane) {
                                inside({ x: result.paneX, y: result.paneY,
                                    width: result.paneWidth, height: result.paneHeight },
                                width, height, `pane: ${label}`);
                                if (result.stacked) assert.ok(result.paneY >= controlBottom, label);
                                else assert.ok(column.x + column.width <= result.paneX, label);
                            }
                        }
                    }
                }
            }
        }
    }
});

test('480x1920 retains full-size controls and a usable stacked pane', () => {
    for (const pane of ['lyrics', 'queue']) {
        const result = layout.measure(480, 1920, 250, 0, pane, 1, 9 / 16);
        assert.equal(result.controlsScale, 1);
        assert.equal(result.stacked, true);
        assert.ok(result.paneHeight >= 240);
        assert.ok(result.artHeight >= 700);
    }
});

test('short fullscreen displays reserve the measured control height', () => {
    for (const width of [980, 1920]) {
        const result = layout.measure(width, 480, 320, 1, 'lyrics', 0, 9 / 16);
        assert.equal(result.controlsScale, 1);
        assert.ok(result.artHeight < 160);
        assert.ok(result.controlsY + 320 <= 480);
    }
});

test('idle grows the solo cover and hands the lyrics pane more room', () => {
    const awake = layout.measure(1920, 1080, 120, 0, '', 0, 1, 0, 0);
    const idle = layout.measure(1920, 1080, 120, 0, '', 0, 1, 0, 1);
    assert.ok(idle.artSize > awake.artSize + 60);
    const split = layout.measure(1920, 1080, 120, 1, 'lyrics', 0, 1, 0, 0);
    const focused = layout.measure(1920, 1080, 120, 1, 'lyrics', 0, 1, 1, 1);
    assert.ok(focused.paneX < split.paneX - 100);
    assert.ok(focused.paneWidth > split.paneWidth + 100);
    assert.ok(focused.artSize < split.artSize - 100);
});

test('transport buttons fit narrow columns and keep usable targets', () => {
    for (const width of [240, 320, 480, 720, 980, 1920]) {
        const column = layout.columns(width, width >= 980 ? 1 : 0);
        const transport = layout.transport(column.width);
        const total = transport.sizes.reduce((sum, size) => sum + size, 0);
        assert.ok(total + transport.spacing * 4 <= column.width + 0.001);
        assert.ok(transport.sizes.every(size => size >= 32));
    }
});

test('split and Canvas animation fractions preserve viewport bounds', () => {
    for (const split of [0, 0.1, 0.5, 0.9, 1]) {
        const column = layout.columns(980, split);
        const result = layout.measure(980, 480, 250, split, 'queue', 1.12, 9 / 16);
        inside({ x: column.x, y: result.columnY, width: column.width,
            height: result.artHeight }, 980, 480, 'animated artwork');
        inside({ x: result.controlsX, y: result.controlsY,
            width: column.width * result.controlsScale,
            height: 250 * result.controlsScale }, 980, 480, 'animated controls');
    }
});
