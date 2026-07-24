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
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 1.25);
    const width = Math.max(2, Math.round(window.innerWidth * pixelRatio));
    const height = Math.max(2, Math.round(window.innerHeight * pixelRatio));
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
