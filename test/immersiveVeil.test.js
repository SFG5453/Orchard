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

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  VEIL_MAX_ALPHA,
  VEIL_MIN_ALPHA,
  VEIL_MUTED_CONTRAST,
  VEIL_TEXT_CONTRAST,
  compositeBackdrop,
  contrastRatio,
  solveVeilAlpha
} from '../src/app/appearance/immersiveVeil.js';

const DARK = {
  veilRgb: [3, 7, 4],
  pageRgb: [0, 0, 0],
  textRgb: [246, 246, 246],
  mutedRgb: [141, 141, 141]
};

const LIGHT = {
  veilRgb: [248, 250, 248],
  pageRgb: [244, 246, 244],
  textRgb: [20, 23, 20],
  mutedRgb: [104, 112, 105],
  minAlpha: 0.7
};

function readable(theme, artworkRgb, veilAlpha) {
  const backdrop = compositeBackdrop({ ...theme, artworkRgb, veilAlpha });
  return {
    text: contrastRatio(theme.textRgb, backdrop),
    muted: contrastRatio(theme.mutedRgb, backdrop)
  };
}

test('relative luminance matches the WCAG reference points', () => {
  assert.equal(relativeLuminanceOf([255, 255, 255]).toFixed(4), '1.0000');
  assert.equal(relativeLuminanceOf([0, 0, 0]).toFixed(4), '0.0000');
  assert.equal(contrastRatio([255, 255, 255], [0, 0, 0]).toFixed(1), '21.0');
});

function relativeLuminanceOf(rgb) {
  // Derived through the exported ratio so the test does not duplicate the table.
  return (contrastRatio(rgb, [0, 0, 0]) * 0.05) - 0.05;
}

test('a dark cover keeps the veil it already had', () => {
  const alpha = solveVeilAlpha({ ...DARK, artworkRgb: [18, 22, 19] });
  assert.equal(alpha, VEIL_MIN_ALPHA);
});

// The reported case: a mid-grey cover under the old fixed veil composited to
// roughly the lightness of the secondary text.
test('a grey cover is unreadable at the old fixed veil and readable after solving', () => {
  const grey = [150, 150, 150];

  const before = readable(DARK, grey, VEIL_MIN_ALPHA);
  assert.ok(before.muted < VEIL_MUTED_CONTRAST, `secondary text was already fine at ${before.muted.toFixed(2)}:1`);

  const alpha = solveVeilAlpha({ ...DARK, artworkRgb: grey });
  const after = readable(DARK, grey, alpha);
  assert.ok(alpha > VEIL_MIN_ALPHA);
  assert.ok(after.text >= VEIL_TEXT_CONTRAST, `body text at ${after.text.toFixed(2)}:1`);
  assert.ok(after.muted >= VEIL_MUTED_CONTRAST, `secondary text at ${after.muted.toFixed(2)}:1`);
});

test('the solved veil is the smallest one that works, not the heaviest', () => {
  const grey = [150, 150, 150];
  const alpha = solveVeilAlpha({ ...DARK, artworkRgb: grey });
  const lighter = readable(DARK, grey, alpha - 0.02);

  assert.ok(alpha < VEIL_MAX_ALPHA);
  assert.ok(
    lighter.text < VEIL_TEXT_CONTRAST || lighter.muted < VEIL_MUTED_CONTRAST,
    'a lighter veil would also have passed, so the solve is not minimal'
  );
});

test('even a white cover is solved inside the ceiling, so artwork stays visible', () => {
  const alpha = solveVeilAlpha({ ...DARK, artworkRgb: [255, 255, 255] });
  const after = readable(DARK, [255, 255, 255], alpha);

  assert.ok(alpha < VEIL_MAX_ALPHA, `the hardest case needed ${alpha.toFixed(3)}`);
  assert.ok(after.text >= VEIL_TEXT_CONTRAST);
  assert.ok(after.muted >= VEIL_MUTED_CONTRAST);
});

test('the light theme never drops below the opacity it shipped with', () => {
  const alpha = solveVeilAlpha({ ...LIGHT, artworkRgb: [250, 250, 250], minAlpha: LIGHT.minAlpha });
  assert.ok(alpha >= LIGHT.minAlpha);
});

test('a dark cover under the light theme is pushed lighter, not darker', () => {
  const dark = [12, 14, 12];
  const alpha = solveVeilAlpha({ ...LIGHT, artworkRgb: dark, minAlpha: LIGHT.minAlpha });
  const after = readable({ ...LIGHT }, dark, alpha);

  assert.ok(alpha > LIGHT.minAlpha, 'the floor alone cannot carry a near-black cover');
  assert.ok(after.text >= VEIL_TEXT_CONTRAST, `body text at ${after.text.toFixed(2)}:1`);
});

test('a heavier background intensity is accounted for rather than ignored', () => {
  const grey = [150, 150, 150];
  const light = solveVeilAlpha({ ...DARK, artworkRgb: grey, backgroundOpacity: 0.5 });
  const heavy = solveVeilAlpha({ ...DARK, artworkRgb: grey, backgroundOpacity: 1 });

  assert.ok(heavy > light, 'a more opaque artwork layer needs a heavier veil');
});
