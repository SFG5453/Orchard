// Installs Pixi's CSP-safe shader and uniform synchronizers. The extension's
// historical name is misleading: these polyfills avoid unsafe-eval entirely.
import 'pixi.js/unsafe-eval';
import {
  Application,
  BlurFilter,
  Container,
  DisplacementFilter,
  ImageSource,
  Sprite,
  Texture
} from 'pixi.js';
import { gsap } from 'gsap';
import {
  ambientArtworkBlur,
  backgroundResizeTarget,
  backgroundViewportSize,
  coverScale,
  createDisplacementCanvas,
  loadArtworkImage,
  motionParametersForUrl,
  normalizeBackgroundUrl,
  rgbToTint
} from './backgroundUtils.js';
import {
  FALLBACK_ARTWORK_PALETTE,
  getArtworkPalette,
  interpolatePalette
} from './useArtworkPalette.js';

const TRANSITION_SECONDS = 2.2;
const MAX_RENDER_RESOLUTION = 1.35;

function copyPalette(palette) {
  return Object.fromEntries(Object.entries(palette).map(([name, rgb]) => [name, [...rgb]]));
}

export class PixiArtworkBackground {
  constructor(canvas) {
    this.canvas = canvas;
    this.app = null;
    this.resizeObserver = null;
    this.resizeTarget = null;
    this.ready = false;
    this.destroyed = false;
    this.enabled = true;
    this.visible = true;
    this.playing = false;
    this.motionEnabled = true;
    this.reducedMotion = false;
    this.hasSize = false;
    this.width = 2;
    this.height = 2;
    this.elapsed = 0;
    this.requestId = 0;
    this.requestedArtwork = '';
    this.currentArtwork = '';
    this.loadingArtwork = '';
    this.layers = new Set();
    this.currentLayer = null;
    this.transitioning = false;
    this.transitionTimeline = null;
    this.motionState = motionParametersForUrl('orchard');
    const fallback = copyPalette(FALLBACK_ARTWORK_PALETTE);
    this.paletteTween = { mix: 1, from: fallback, to: fallback };
  }

  async initialize() {
    if (this.destroyed || this.app) return;
    const app = new Application();
    this.app = app;

    try {
      const viewport = backgroundViewportSize(this.canvas);
      await app.init({
        antialias: false,
        autoDensity: true,
        autoStart: false,
        background: '#0b0f0c',
        canvas: this.canvas,
        height: viewport.height,
        powerPreference: 'low-power',
        preference: 'webgl',
        resolution: Math.min(window.devicePixelRatio || 1, MAX_RENDER_RESOLUTION),
        width: viewport.width
      });
    } catch (error) {
      if (!this.destroyed) console.warn('GPU artwork backgrounds are unavailable', error);
      this.app = null;
      return;
    }

    if (this.destroyed) {
      app.destroy({ removeView: false }, { children: true });
      return;
    }

    this.createScene();
    app.ticker.add((ticker) => this.update(ticker.deltaMS / 1000));
    this.resizeObserver = new ResizeObserver(() => this.resize());
    // Pixi's auto-density sizing writes fixed pixel dimensions to the canvas.
    // Observe its viewport-sized wrapper so maximizing the window still
    // triggers a renderer resize instead of leaving the startup-sized box.
    this.resizeTarget = backgroundResizeTarget(this.canvas);
    this.resizeObserver.observe(this.resizeTarget);
    this.ready = true;
    this.resize();
    this.applyPalette();
    void this.loadRequestedArtwork();
  }

  createScene() {
    this.artworkRoot = new Container();

    this.displacementTexture = Texture.from(createDisplacementCanvas(), true);
    this.displacementTexture.source.addressMode = 'repeat';
    this.displacementSprite = new Sprite(this.displacementTexture);
    this.displacementSprite.alpha = 0;
    this.displacementFilter = new DisplacementFilter({
      sprite: this.displacementSprite,
      scale: { x: 7, y: 5 }
    });
    this.blurFilter = new BlurFilter({
      kernelSize: 9,
      quality: 2,
      resolution: 0.5,
      strength: 96
    });
    this.blurFilter.repeatEdgePixels = true;
    this.artworkRoot.filters = [this.displacementFilter, this.blurFilter];
    this.artworkRoot.alpha = 0.94;

    this.app.stage.eventMode = 'none';
    this.app.stage.addChild(
      this.displacementSprite,
      this.artworkRoot
    );
  }

  setArtwork(value) {
    const source = normalizeBackgroundUrl(value);
    if (source === this.requestedArtwork && (source === this.currentArtwork || source === this.loadingArtwork)) return;
    this.requestedArtwork = source;
    this.requestId += 1;
    if (this.ready && source) void this.loadRequestedArtwork();
  }

  async loadRequestedArtwork() {
    const source = this.requestedArtwork;
    const requestId = this.requestId;
    if (!this.ready || !source || source === this.currentArtwork) return;
    this.loadingArtwork = source;

    try {
      // The old layer remains owned and visible until both decoding and palette
      // extraction finish; requestId prevents stale skips from touching the stage.
      const image = await loadArtworkImage(source);
      if (this.isStale(requestId, source)) return;
      const palette = await getArtworkPalette(source, image);
      if (this.isStale(requestId, source)) return;
      const layer = this.createArtworkLayer(source, image);
      this.transitionTo(layer, palette);
    } catch (error) {
      if (!this.isStale(requestId, source)) {
        console.warn('Unable to load generated artwork background', error);
      }
    } finally {
      if (requestId === this.requestId) this.loadingArtwork = '';
    }
  }

  isStale(requestId, source) {
    return this.destroyed || requestId !== this.requestId || source !== this.requestedArtwork;
  }

  createArtworkLayer(source, image) {
    const texture = new Texture({
      label: `immersive-artwork:${source}`,
      source: new ImageSource({ resource: image })
    });
    const sprite = new Sprite(texture);
    sprite.anchor.set(0.5);
    sprite.alpha = 0;
    this.artworkRoot.addChild(sprite);
    const layer = { source, sprite, texture, baseScale: 1 };
    this.layers.add(layer);
    this.fitLayer(layer);
    return layer;
  }

  transitionTo(layer, palette) {
    this.transitionTimeline?.kill();
    this.transitionTimeline = null;
    this.transitioning = false;

    const oldLayers = [...this.layers].filter((candidate) => candidate !== layer);
    const displayedPalette = interpolatePalette(
      this.paletteTween.from,
      this.paletteTween.to,
      this.paletteTween.mix
    );
    this.paletteTween.from = displayedPalette;
    this.paletteTween.to = copyPalette(palette);
    this.paletteTween.mix = 0;
    const motionTarget = motionParametersForUrl(layer.source);
    const duration = this.reducedMotion ? 0 : TRANSITION_SECONDS;
    this.currentLayer = layer;
    this.currentArtwork = layer.source;

    if (!duration) {
      oldLayers.forEach((oldLayer) => this.destroyLayer(oldLayer));
      layer.sprite.alpha = 1;
      Object.assign(this.motionState, motionTarget);
      this.paletteTween.mix = 1;
      this.applyPalette();
      this.renderOnce();
      return;
    }

    this.transitioning = true;
    const timeline = gsap.timeline({
      defaults: { duration, ease: 'sine.inOut' },
      onComplete: () => {
        if (this.transitionTimeline !== timeline) return;
        oldLayers.forEach((oldLayer) => this.destroyLayer(oldLayer));
        this.transitioning = false;
        this.transitionTimeline = null;
        this.syncTicker();
      }
    });
    this.transitionTimeline = timeline;
    oldLayers.forEach((oldLayer) => timeline.to(oldLayer.sprite, { alpha: 0 }, 0));
    timeline.to(layer.sprite, { alpha: 1 }, 0);
    timeline.to(this.paletteTween, { mix: 1, onUpdate: () => this.applyPalette() }, 0);
    timeline.to(this.motionState, motionTarget, 0);

    if (!this.enabled || !this.visible) timeline.pause();
    this.syncTicker();
  }

  applyPalette() {
    if (!this.app?.renderer?.background) return;
    const palette = interpolatePalette(this.paletteTween.from, this.paletteTween.to, this.paletteTween.mix);
    this.app.renderer.background.color = rgbToTint(palette.darkMuted);
  }

  update(deltaSeconds) {
    if (this.shouldAnimateAmbient()) this.elapsed += deltaSeconds * this.motionState.speed;
    this.applyMotion();
  }

  applyMotion() {
    if (!this.ready) return;
    const time = this.elapsed + this.motionState.phase;
    const driftX = Math.sin(time / 7.1) * this.width * this.motionState.translateX;
    const driftY = Math.cos(time / 9.7) * this.height * this.motionState.translateY;
    const scaleDrift = 1 + (Math.sin(time / 12.9) * this.motionState.scale);
    const rotation = Math.sin(time / 15.7) * this.motionState.rotation;

    for (const layer of this.layers) {
      layer.sprite.position.set((this.width / 2) + driftX, (this.height / 2) + driftY);
      layer.sprite.scale.set(layer.baseScale * scaleDrift);
      layer.sprite.rotation = rotation;
    }

    this.displacementSprite.position.set(
      Math.sin(time / 11.9) * this.width * 0.08,
      Math.cos(time / 13.7) * this.height * 0.08
    );
    const distortion = this.motionEnabled && !this.reducedMotion ? this.motionState.distortion : 0;
    // Pixi accepts any PointData for this option and preserves object-form
    // values, so scale is not guaranteed to be an ObservablePoint with set().
    this.displacementFilter.scale.x = distortion;
    this.displacementFilter.scale.y = distortion * 0.72;
  }

  resize() {
    if (!this.app || !this.ready) return;
    const viewport = backgroundViewportSize(this.canvas);
    this.hasSize = viewport.width >= 2 && viewport.height >= 2;
    if (!this.hasSize) {
      this.app.stop();
      return;
    }

    this.width = viewport.width;
    this.height = viewport.height;
    const resolution = Math.min(window.devicePixelRatio || 1, MAX_RENDER_RESOLUTION);
    this.app.renderer.resize(this.width, this.height, resolution);
    this.blurFilter.strength = ambientArtworkBlur(this.width, this.height);
    this.displacementSprite.width = Math.max(this.width, this.height) * 0.8;
    this.displacementSprite.height = Math.max(this.width, this.height) * 0.8;
    this.layers.forEach((layer) => this.fitLayer(layer));
    this.applyMotion();
    this.syncTicker();
  }

  fitLayer(layer) {
    layer.baseScale = coverScale(
      layer.texture.width,
      layer.texture.height,
      this.width,
      this.height,
      1.28
    );
  }

  shouldAnimateAmbient() {
    return this.enabled && this.visible && this.playing && this.motionEnabled && !this.reducedMotion && this.hasSize;
  }

  setEnabled(value) {
    this.enabled = Boolean(value);
    if (this.enabled && this.visible) this.transitionTimeline?.resume();
    else this.transitionTimeline?.pause();
    if (this.enabled && this.requestedArtwork) void this.loadRequestedArtwork();
    this.syncTicker();
  }

  setPlaying(value) {
    this.playing = Boolean(value);
    this.syncTicker();
  }

  setMotionEnabled(value) {
    this.motionEnabled = Boolean(value);
    this.applyMotion();
    this.syncTicker();
  }

  setVisible(value) {
    this.visible = Boolean(value);
    if (this.visible && this.enabled) this.transitionTimeline?.resume();
    else this.transitionTimeline?.pause();
    this.syncTicker();
  }

  setReducedMotion(value) {
    this.reducedMotion = Boolean(value);
    if (this.reducedMotion && this.transitionTimeline) this.transitionTimeline.progress(1);
    this.applyMotion();
    this.syncTicker();
  }

  syncTicker() {
    if (!this.app || !this.ready) return;
    const shouldRun = this.hasSize && this.visible && this.enabled && (this.transitioning || this.shouldAnimateAmbient());
    if (shouldRun) {
      this.app.start();
      return;
    }

    this.app.stop();
    this.renderOnce();
  }

  renderOnce() {
    if (this.app && this.ready && this.visible && this.hasSize) this.app.render();
  }

  destroyLayer(layer) {
    if (!this.layers.has(layer)) return;
    this.layers.delete(layer);
    layer.sprite.removeFromParent();
    layer.sprite.destroy();
    // Each layer owns its ImageSource; destroy it only after the crossfade so
    // the outgoing texture can never disappear midway through a transition.
    layer.texture.destroy(true);
  }

  destroy() {
    this.destroyed = true;
    this.requestId += 1;
    this.transitionTimeline?.kill();
    this.transitionTimeline = null;
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.resizeTarget = null;
    this.layers.forEach((layer) => this.destroyLayer(layer));
    this.blurFilter?.destroy();
    this.displacementFilter?.destroy();
    this.app?.destroy({ removeView: false }, { children: true });
    this.displacementTexture?.destroy(true);
    this.app = null;
    this.ready = false;
  }
}
