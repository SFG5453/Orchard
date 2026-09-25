export type QobuzQuality = 'auto' | 'lossless' | 'hires';
export type FetchLike = (input: string | URL, init?: any) => Promise<any>;

export interface QobuzLogger {
  info?(message: string, detail?: unknown): void;
  warn?(message: string, detail?: unknown): void;
}

export interface QobuzCredentials {
  token: string;
  userId: number;
}

export interface QobuzBootstrap {
  appId: string;
  bundlePath?: string;
  oauthPrivateKey: string;
  rngInit: string;
}

export interface QobuzBootstrapLoader {
  clear(): void;
  get(options?: { refresh?: boolean }): Promise<QobuzBootstrap>;
}

export interface CanonicalTrack {
  title: string;
  artists: string[];
  album: string;
  durationMs: number;
  isrc: string;
  explicit: boolean;
}

export type CanonicalTrackInput = Partial<CanonicalTrack> & {
  artist?: string;
  durationSeconds?: number;
};

export interface QobuzMatch {
  provider: 'qobuz';
  qobuzTrackId: number;
  method: string;
  confidence: number;
  title: string;
  artist: string;
  album: string;
  durationSeconds: number;
  isrc: string;
  explicit: boolean;
  hires: boolean;
  bitDepth?: number;
  sampleRate?: number;
}

/** Catalog maximum quality. sampleRate is normalized to Hz; source values are kHz. */
export interface QobuzQualityMetadata {
  bitDepth?: number;
  /** Sample rate in Hz; Qobuz catalog responses report this value in kHz. */
  sampleRate?: number;
  channels?: number;
  hiresStreamable?: boolean;
  streamable?: boolean;
}

export interface QobuzAlbumQuality extends QobuzQualityMetadata {
  albumId: string;
}

export interface QobuzTrackQuality extends QobuzQualityMetadata {
  trackId: number;
}

export interface QobuzPlaybackSource {
  provider: 'qobuz';
  playbackId: string;
  codec: 'flac';
  mimeType: 'audio/flac';
  preloadMode: 'metadata';
  bitDepth?: number;
  sampleRate?: number;
  channels: number;
  expiresAt: number;
  durationSeconds: number;
  formatId: number;
  segmentCount: number;
  seekPointCount: number;
  tableSamples: number;
  totalSamples: number;
  totalBytes: number;
}

export interface QobuzClient {
  ensureSession(options?: { refresh?: boolean }): Promise<unknown>;
  reportStreamingEnd(event: Record<string, unknown>): Promise<unknown>;
  reportStreamingStart(source: Record<string, unknown>): Promise<unknown>;
  reset(): void;
  search(query: string): Promise<any>;
  streamingInfo(trackId: number | string, quality: QobuzQuality): Promise<any>;
  albumQuality(albumId: string): Promise<QobuzAlbumQuality>;
  trackQuality(trackId: number | string): Promise<QobuzTrackQuality>;
  trackQualities(trackIds: Array<number | string>): Promise<QobuzTrackQuality[]>;
  getAlbumQuality(albumId: string): Promise<QobuzAlbumQuality>;
  getTrackQuality(trackId: number | string): Promise<QobuzTrackQuality>;
  getTrackQualities(trackIds: Array<number | string>): Promise<QobuzTrackQuality[]>;
}

export interface QobuzService {
  bootstrap: QobuzBootstrapLoader;
  client: QobuzClient;
  close(): Promise<void>;
  matchTrack(track: CanonicalTrackInput): Promise<QobuzMatch | null>;
  playbackEnded(playbackId: string, positionSeconds?: number): Promise<void>;
  playbackStarted(playbackId: string, positionSeconds?: number): Promise<void>;
  proxyStream(playbackId: string, request: any, response: any): Promise<void>;
  resolveStream(match: QobuzMatch, quality?: QobuzQuality): Promise<QobuzPlaybackSource>;
  resolveTrack(track: CanonicalTrackInput, quality?: QobuzQuality): Promise<{
    match: QobuzMatch;
    source: QobuzPlaybackSource;
  } | null>;
  search(query: string): Promise<any>;
  streamingInfo(trackId: number | string, quality: QobuzQuality): Promise<any>;
  albumQuality(albumId: string): Promise<QobuzAlbumQuality>;
  trackQuality(trackId: number | string): Promise<QobuzTrackQuality>;
  trackQualities(trackIds: Array<number | string>): Promise<QobuzTrackQuality[]>;
  getAlbumQuality(albumId: string): Promise<QobuzAlbumQuality>;
  getTrackQuality(trackId: number | string): Promise<QobuzTrackQuality>;
  getTrackQualities(trackIds: Array<number | string>): Promise<QobuzTrackQuality[]>;
}

export interface CreateQobuzOptions {
  bootstrap?: QobuzBootstrapLoader;
  bootstrapMaxAgeMs?: number;
  credentials(): Promise<QobuzCredentials | null> | QobuzCredentials | null;
  fetchImpl?: FetchLike;
  logger?: QobuzLogger;
  matcherCacheTtlMs?: number;
  softwareVersion?: string;
}

export function createQobuz(options: CreateQobuzOptions): QobuzService;
export function createQobuzBootstrapLoader(options?: {
  fetchImpl?: FetchLike;
  maxAgeMs?: number;
}): QobuzBootstrapLoader;
export function fetchQobuzBootstrap(options?: { fetchImpl?: FetchLike }): Promise<QobuzBootstrap>;
export function extractQobuzBootstrap(bundle: string, bundlePath?: string): QobuzBootstrap;
export function createQobuzAuthorizationUrl(options: { appId: string; redirectUrl: string }): URL;
export function exchangeQobuzAuthorizationCode(options: {
  code: string;
  fetchImpl?: FetchLike;
  web: QobuzBootstrap;
}): Promise<QobuzCredentials>;
export function createQobuzClient(options: {
  bootstrap: QobuzBootstrapLoader;
  credentials: CreateQobuzOptions['credentials'];
  fetchImpl?: FetchLike;
  softwareVersion?: string;
}): QobuzClient;
export function qobuzRequestSignature(
  method: string,
  args: Record<string, string | number>,
  timestamp: string | number,
  secret: string
): string;
export function createQobuzMatcher(options: {
  search(query: string): Promise<any>;
  cacheTtlMs?: number;
}): (track: CanonicalTrack) => Promise<QobuzMatch | null>;
export function normalizedQobuzText(value: unknown): string;
export function selectQobuzMatch(
  target: CanonicalTrack,
  candidates: any[],
  method?: string
): QobuzMatch | null;
export function createQobuzPlayback(options: {
  client: QobuzClient;
  fetchImpl?: FetchLike;
  logger?: QobuzLogger;
  reporter: ReturnType<typeof createQobuzReporter>;
}): {
  clear(): void;
  playbackEnded(playbackId: string, positionSeconds?: number): Promise<void>;
  playbackStarted(playbackId: string, positionSeconds?: number): Promise<void>;
  proxyStream(playbackId: string, request: any, response: any): Promise<void>;
  resolveStream(match: QobuzMatch, quality: QobuzQuality): Promise<QobuzPlaybackSource>;
};
export function createQobuzReporter(options: { client: QobuzClient; logger?: QobuzLogger }): {
  close(): Promise<void>;
  ended(playbackId: string, positionSeconds?: number): Promise<void>;
  started(playbackId: string, source: Record<string, any>, positionSeconds?: number): Promise<void>;
};

export interface QobuzInitSegment {
  flacHeader: Uint8Array;
  segmentTable: Array<{ byteLength: number; sampleCount: number; byteOffset: number }>;
  totalLength: number;
  sampleRate: number;
  channels: number;
  bitDepth: number;
  totalSamples: number;
  seekPoints: Array<{ sampleNumber: number; streamOffset: number; frameSamples: number }>;
  tableSamples: number;
}

export interface QobuzAudioSegment {
  data: Uint8Array;
  dataOffset: number;
  frames: Array<{ flags: number; iv: Uint8Array; size: number; skip: number }>;
  mediaEnd: number;
}

export function parseQobuzInitSegment(input: ArrayBuffer | Uint8Array): QobuzInitSegment;
export function parseQobuzAudioSegment(input: ArrayBuffer | Uint8Array): QobuzAudioSegment;
export function deriveQobuzSessionKey(infos: string, rngInit: string): Uint8Array;
export function unwrapQobuzContentKey(sessionKey: Uint8Array, wrappedValue: string): Uint8Array;
export function decryptQobuzAudioSegment(
  input: ArrayBuffer | Uint8Array,
  contentKey?: Uint8Array | null
): Uint8Array;
export function canonicalTrack(input?: CanonicalTrackInput): CanonicalTrack;
export function normalizeQobuzQuality(value: unknown): QobuzQuality;
export function normalizeQobuzAlbumQuality(raw: unknown, fallbackId?: string): QobuzAlbumQuality;
export function normalizeQobuzTrackQuality(raw: unknown, fallbackId?: number | string): QobuzTrackQuality;

export const QOBUZ_AUDIO_QUALITIES: readonly QobuzQuality[];
export const QOBUZ_BASE_URL: string;
export const QOBUZ_PLAY_URL: string;
export const QOBUZ_USER_AGENT: string;
export const QOBUZ_FORMAT_IDS: Readonly<Record<QobuzQuality, number>>;
export const QOBUZ_CMAF_UUIDS: Readonly<{ init: string; segment: string }>;
