// SPDX-License-Identifier: AGPL-3.0-or-later
.pragma library

function transport(width) {
    var compact = width < 340;
    var sizes = compact ? [32, 36, 52, 36, 32] : [40, 52, 68, 52, 40];
    var total = sizes.reduce(function(sum, size) { return sum + size; }, 0);
    return { sizes: sizes, spacing: Math.min(compact ? 10 : 18, Math.max(0, (width - total) / 4)) };
}

// focus (0..1) narrows the player column so an idle lyrics pane can take the room.
function columns(width, split, focus) {
    var wide = width >= 980;
    var fraction = wide ? Math.max(0, Math.min(1, split)) : 0;
    var lean = 0.12 * Math.max(0, Math.min(1, focus || 0));
    var margin = wide ? Math.max(40, width * 0.05) : Math.max(16, Math.min(40, width * 0.05));
    var solo = Math.max(0, width - margin * 2);
    var left = Math.max(0, width * (0.46 - lean) - margin);
    var column = Math.min(560, solo + (left - solo) * fraction);
    var center = (width - column) / 2;
    return {
        wide: wide, margin: margin, width: column, paneX: width * (0.5 - lean),
        x: center + (margin + (left - column) / 2 - center) * fraction
    };
}

// idle (0..1) lets the cover grow into the room the hidden controls leave.
function measure(width, height, controlsHeight, split, pane, canvasShape, canvasAspect, focus, idle) {
    var column = columns(width, split, focus);
    var top = Math.min(64, height * 0.16);
    var bottom = Math.min(40, height * 0.06);
    var available = Math.max(0, height - top - bottom);
    var stacked = !column.wide && pane !== "";
    var paneGap = stacked ? Math.min(24, available * 0.05) : 0;
    var paneReserve = stacked ? Math.min(240, available * 0.35) : 0;
    var playerHeight = Math.max(0, available - paneReserve - paneGap);
    var scale = Math.min(1, playerHeight / Math.max(1, controlsHeight));
    var controlHeight = controlsHeight * scale;
    var gap = Math.min(28, Math.max(0, playerHeight - controlHeight));
    var artBudget = Math.max(0, playerHeight - controlHeight - gap);
    var grow = 120 * Math.max(0, Math.min(1, idle || 0));
    // Only the solo layout lets the cover outgrow its column; a split keeps it clear of the pane.
    var solo = column.wide ? 1 - Math.max(0, Math.min(1, split)) : 1;
    var room = Math.min(column.width + grow * solo, Math.max(column.width, width - column.margin * 2));
    // An idle lyrics pane also shrinks the cover so the words lead.
    var lean = 140 * Math.max(0, Math.min(1, focus || 0)) * (1 - solo);
    var art = Math.min((column.wide ? 520 : 560) + grow * solo - lean, room, artBudget);
    var aspect = Math.max(0.5, Math.min(1, canvasAspect));
    var portrait = Math.min(760 + grow * solo - lean, artBudget, room / aspect);
    // Canvas animations stay within the measured artwork budget.
    var shape = Math.max(0, Math.min(1, canvasShape));
    var artHeight = art + (portrait - art) * shape;
    var artWidth = art + (portrait * aspect - art) * shape;
    var contentHeight = artHeight + gap + controlHeight;
    var y = top + (stacked ? 0 : Math.max(0, (available - contentHeight) / 2));
    var controlsY = y + artHeight + gap;
    var paneY = stacked ? controlsY + controlHeight + paneGap : top;
    return {
        top: top, availableHeight: available, artSize: art,
        artHeight: artHeight, artWidth: artWidth, columnY: y,
        controlsScale: scale, controlsY: controlsY,
        controlsX: column.x + column.width * (1 - scale) / 2,
        paneX: stacked ? column.margin : column.paneX,
        paneY: paneY,
        paneWidth: stacked ? width - column.margin * 2 : Math.max(0, width - column.paneX - column.margin),
        paneHeight: Math.max(0, available - (paneY - top)), stacked: stacked
    };
}
