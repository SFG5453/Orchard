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

// The immersive backdrop used to sit under a veil of one fixed opacity, which
// assumed every cover was dark. A pale one, greyscale especially, composited to
// roughly the same lightness as the body text and the page turned into grey on
// grey. Raising the background-intensity setting did not help, because that
// scales the whole layer including the veil.
//
// So the veil is solved for instead of assumed: sample the artwork, work out
// what the layer stack composites to, and pick the smallest opacity that still
// clears a contrast target for both text colours. Dark covers land on the
// existing default and look unchanged.

// WCAG 2.1 relative luminance, and its contrast ratio.
export function relativeLuminance([r, g, b]) {
  const channel = (value) => {
    const scaled = Math.min(255, Math.max(0, Number(value) || 0)) / 255;
    return scaled <= 0.04045 ? scaled / 12.92 : ((scaled + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

export function contrastRatio(left, right) {
  const a = relativeLuminance(left);
  const b = relativeLuminance(right);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

// Body text has to clear AA for normal text. Secondary text is held to the
// large-text ratio: pinning it at 4.5 would force a veil so heavy that the
// artwork stops reading as artwork at all, which is the feature.
export const VEIL_TEXT_CONTRAST = 4.5;
export const VEIL_MUTED_CONTRAST = 3;

// The shipped default stays the floor, so nothing gets lighter than it was.
export const VEIL_MIN_ALPHA = 0.34;
// A ceiling short of 1, so even a white cover leaves the backdrop visible.
export const VEIL_MAX_ALPHA = 0.82;

/**
 * What the viewer actually sees, in sRGB, for a given veil opacity.
 *
 * The stack is `page` at the bottom, then the artwork layer carrying the
 * background-intensity opacity, and the veil painted inside that layer so it is
 * scaled by the same opacity.
 */
export function compositeBackdrop({
  artworkRgb,
  veilRgb,
  pageRgb,
  backgroundOpacity = 0.82,
  veilAlpha = VEIL_MIN_ALPHA
}) {
  return [0, 1, 2].map((channel) => {
    const layer = artworkRgb[channel] * (1 - veilAlpha) + veilRgb[channel] * veilAlpha;
    return pageRgb[channel] * (1 - backgroundOpacity) + layer * backgroundOpacity;
  });
}

/**
 * Smallest veil opacity that clears both contrast targets.
 *
 * Solved by bisection rather than algebraically, because contrast is not linear
 * in the opacity and because this has to hold for the light theme too, where
 * the veil is pale and the text is dark. Contrast rises monotonically with the
 * opacity in both, since each theme's veil sits on the same side of the text as
 * its page colour does.
 *
 * @returns {number} An opacity within [VEIL_MIN_ALPHA, VEIL_MAX_ALPHA]. The
 * ceiling is returned when even that cannot reach the target, because a backdrop
 * that is merely as good as possible beats one that has given up.
 */
export function solveVeilAlpha({
  artworkRgb,
  veilRgb,
  pageRgb,
  textRgb,
  mutedRgb,
  backgroundOpacity = 0.82,
  minAlpha = VEIL_MIN_ALPHA,
  maxAlpha = VEIL_MAX_ALPHA
}) {
  const clears = (veilAlpha) => {
    const backdrop = compositeBackdrop({ artworkRgb, veilRgb, pageRgb, backgroundOpacity, veilAlpha });
    return contrastRatio(textRgb, backdrop) >= VEIL_TEXT_CONTRAST &&
      contrastRatio(mutedRgb, backdrop) >= VEIL_MUTED_CONTRAST;
  };

  if (clears(minAlpha)) return minAlpha;
  if (!clears(maxAlpha)) return maxAlpha;

  let low = minAlpha;
  let high = maxAlpha;
  for (let step = 0; step < 24; step += 1) {
    const middle = (low + high) / 2;
    if (clears(middle)) high = middle;
    else low = middle;
  }
  return high;
}
