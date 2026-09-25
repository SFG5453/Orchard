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

import { Kawarp } from '@kawarp/core';
import { normalizeBackgroundUrl } from './backgroundUtils.js';

const KAWARP_OPTIONS = {
  animationSpeed: 1.38,
  blurPasses: 5,
  dithering: 0.014,
  saturation: 1.24,
  scale: 1.32,
  tintColor: [0.024, 0.04, 0.028],
  tintIntensity: 0.42,
  transitionDuration: 1200,
  warpIntensity: 0.92
};

// This canvas is blurred ambient colour, not detail the user needs to resolve.
// Rendering it at display density wastes a large full-resolution WebGL FBO (and
// the canvas backing store). Keep roughly 60% of CSS resolution and cap the
// total surface at 720p; CSS still stretches it over the entire viewport.
const RENDER_SCALE = 0.6;
const MAX_RENDER_PIXELS = 1280 * 720;

export function immersiveBackingSize(width, height) {
  const cssWidth = Math.max(2, Number(width) || 2);
  const cssHeight = Math.max(2, Number(height) || 2);
  const scaledWidth = cssWidth * RENDER_SCALE;
  const scaledHeight = cssHeight * RENDER_SCALE;
  const pixelScale = Math.min(1, Math.sqrt(MAX_RENDER_PIXELS / (scaledWidth * scaledHeight)));

  return {
    width: Math.max(2, Math.round(scaledWidth * pixelScale)),
    height: Math.max(2, Math.round(scaledHeight * pixelScale))
  };
}

// Kawarp performs its expensive blur when artwork changes, then keeps the
// frame loop to a small domain-warp pass. Static mode renders one warped frame
// and stops, while animated mode resumes that same renderer when playing.
export class KawarpArtworkBackground {
  constructor(canvas) {
    this.canvas = canvas;
    this.renderer = null;
    this.destroyed = false;
    this.enabled = true;
    this.visible = true;
    this.playing = false;
    this.motionEnabled = true;
    this.reducedMotion = false;
    this.source = '';
    this.requestId = 0;
  }

  initialize() {
    if (this.destroyed || this.renderer) return Boolean(this.renderer);
    try {
      this.renderer = new Kawarp(this.canvas, KAWARP_OPTIONS);
      this.resize();
      return true;
    } catch (error) {
      console.warn('Kawarp artwork backgrounds are unavailable', error);
      this.renderer = null;
      return false;
    }
  }

  setArtwork(value) {
    const source = normalizeBackgroundUrl(value);
    if (!this.renderer || source === this.source) return;
    this.source = source;
    const requestId = ++this.requestId;
    if (!source) return;
    this.renderer.transitionDuration = this.shouldAnimate()
      ? KAWARP_OPTIONS.transitionDuration
      : 0;

    void this.renderer.loadImage(source)
      .then(() => {
        if (this.destroyed || requestId !== this.requestId || source !== this.source) return;
        this.renderStill();
        this.syncPlayback();
      })
      .catch((error) => {
        if (!this.destroyed && requestId === this.requestId) {
          console.warn('Unable to load Kawarp artwork background', error);
        }
      });
  }

  resize() {
    if (!this.renderer || this.destroyed) return;
    const bounds = this.canvas.getBoundingClientRect();
    const { width, height } = immersiveBackingSize(
      bounds.width || window.innerWidth,
      bounds.height || window.innerHeight
    );
    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.canvas.width = width;
      this.canvas.height = height;
      this.renderer.resize();
    }
    this.renderStill();
  }

  setEnabled(value) {
    this.enabled = Boolean(value);
    this.syncPlayback();
  }

  setVisible(value) {
    this.visible = Boolean(value);
    this.syncPlayback();
  }

  setPlaying(value) {
    this.playing = Boolean(value);
    this.syncPlayback();
  }

  setMotionEnabled(value) {
    this.motionEnabled = Boolean(value);
    this.renderStill();
    this.syncPlayback();
  }

  setReducedMotion(value) {
    this.reducedMotion = Boolean(value);
    this.renderStill();
    this.syncPlayback();
  }

  shouldAnimate() {
    return this.enabled && this.visible && !this.reducedMotion &&
      this.motionEnabled && this.playing;
  }

  renderStill() {
    if (!this.renderer || !this.source) return;
    this.renderer.stop();
    this.renderer.renderFrame(0);
  }

  syncPlayback() {
    if (!this.renderer) return;
    if (this.shouldAnimate()) {
      this.renderer.start();
      return;
    }
    this.renderStill();
  }

  destroy() {
    this.destroyed = true;
    this.requestId += 1;
    this.source = '';
    this.renderer?.dispose();
    this.renderer = null;
  }
}
