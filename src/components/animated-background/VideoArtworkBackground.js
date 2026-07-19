import { isHlsSource, normalizeBackgroundUrl } from './backgroundUtils.js';

const HLS_MIME_TYPE = 'application/vnd.apple.mpegurl';

export class VideoArtworkBackground {
  constructor(video, { onReady, onFallback }) {
    this.video = video;
    this.onReady = onReady;
    this.onFallback = onFallback;
    this.abortController = null;
    this.hls = null;
    this.requestId = 0;
    this.source = '';
    this.ready = false;
    this.playbackAllowed = false;
  }

  setSource(value) {
    const source = normalizeBackgroundUrl(value);
    if (source === this.source) {
      void this.syncPlayback();
      return;
    }

    this.requestId += 1;
    const requestId = this.requestId;
    this.releaseMedia();
    this.source = source;
    this.onFallback();
    if (!source) return;

    const controller = new AbortController();
    this.abortController = controller;
    this.video.addEventListener('loadeddata', () => void this.handleReady(requestId), {
      once: true,
      signal: controller.signal
    });
    this.video.addEventListener('error', () => this.fail(requestId, new Error('Animated artwork could not be played')), {
      once: true,
      signal: controller.signal
    });

    if (isHlsSource(source)) {
      void this.loadHls(source, requestId);
      return;
    }

    this.video.src = source;
    this.video.load();
  }

  async loadHls(source, requestId) {
    if (this.video.canPlayType(HLS_MIME_TYPE)) {
      this.video.src = source;
      this.video.load();
      return;
    }

    let Hls;
    try {
      ({ default: Hls } = await import('hls.js'));
    } catch (error) {
      this.fail(requestId, error);
      return;
    }

    if (requestId !== this.requestId || source !== this.source) return;
    if (!Hls.isSupported()) {
      this.fail(requestId, new Error('HLS animated artwork is unsupported'));
      return;
    }

    const hls = new Hls({
      backBufferLength: 10,
      enableWorker: true,
      lowLatencyMode: false,
      maxBufferLength: 18
    });
    this.hls = hls;
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (data.fatal) this.fail(requestId, new Error(`HLS animated artwork failed: ${data.details}`));
    });
    hls.loadSource(source);
    hls.attachMedia(this.video);
  }

  async handleReady(requestId) {
    if (requestId !== this.requestId || !this.source) return;
    this.ready = true;

    if (this.playbackAllowed) {
      try {
        await this.video.play();
      } catch (error) {
        this.fail(requestId, error);
        return;
      }
    }

    if (requestId === this.requestId) this.onReady();
  }

  async syncPlayback() {
    if (!this.ready || !this.playbackAllowed) {
      this.video.pause();
      return;
    }

    try {
      await this.video.play();
    } catch (error) {
      this.fail(this.requestId, error);
    }
  }

  setPlaybackAllowed(value) {
    this.playbackAllowed = Boolean(value);
    void this.syncPlayback();
  }

  fail(requestId, error) {
    if (requestId !== this.requestId) return;
    console.warn('Unable to use animated artwork background', error);
    this.requestId += 1;
    this.source = '';
    this.releaseMedia();
    this.onFallback();
  }

  releaseMedia() {
    this.abortController?.abort();
    this.abortController = null;
    // HLS owns MediaSource buffers and its worker, so every source change must
    // destroy the instance before the element is reused.
    this.hls?.destroy();
    this.hls = null;
    this.ready = false;
    this.video.pause();
    this.video.removeAttribute('src');
    this.video.load();
  }

  destroy() {
    this.requestId += 1;
    this.source = '';
    this.releaseMedia();
  }
}
