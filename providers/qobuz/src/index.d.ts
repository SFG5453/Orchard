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

export type QobuzQuality = 'auto' | 'lossless' | 'hires';
export type FetchLike = (input: string | URL, init?: any) => Promise<any>;

export interface QobuzLogger {
  info?(message: string, detail?: unknown): void;
  warn?(message: string, detail?: unknown): void;
}

export interface QobuzByteRange {
  bytes: Uint8Array;
  start: number;
  end: number;
  totalLength: number;
  mimeType: 'audio/flac';
}

export interface QobuzCredentials {
  token: string;
  userId: number;
  /** The user's own Qobuz app; the secret signs track/getFileUrl. */
  appId: string;
  appSecret: string;
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
  bitDepth?: number;
  sampleRate?: number;
  durationSeconds: number;
  formatId: number;
  totalBytes: number;
}

export interface QobuzClient {
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
  client: QobuzClient;
  close(): Promise<void>;
  matchTrack(track: CanonicalTrackInput): Promise<QobuzMatch | null>;
  playbackEnded(playbackId: string, positionSeconds?: number): Promise<void>;
  playbackStarted(playbackId: string, positionSeconds?: number): Promise<void>;
  /** Returns at most one chunk, bounded by the requested range. */
  readChunk(playbackId: string, range?: { start?: number; end?: number }): Promise<QobuzByteRange>;
  readRange(playbackId: string, range?: { start?: number; end?: number }): Promise<QobuzByteRange>;
  release(playbackId: string): void;
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
  credentials(): Promise<QobuzCredentials | null> | QobuzCredentials | null;
  fetchImpl?: FetchLike;
  logger?: QobuzLogger;
  matcherCacheTtlMs?: number;
  randomUuid?(): string;
  softwareVersion?: string;
}

export function createQobuz(options: CreateQobuzOptions): QobuzService;
export function verifyQobuzToken(options: {
  token: string;
  userId: number | string;
  appId: string;
  appSecret: string;
  fetchImpl?: FetchLike;
}): Promise<QobuzCredentials>;
export function createQobuzClient(options: {
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
  randomUuid?(): string;
  reporter: ReturnType<typeof createQobuzReporter>;
}): {
  clear(): void;
  playbackEnded(playbackId: string, positionSeconds?: number): Promise<void>;
  playbackStarted(playbackId: string, positionSeconds?: number): Promise<void>;
  readChunk(playbackId: string, range?: { start?: number; end?: number }): Promise<QobuzByteRange>;
  readRange(playbackId: string, range?: { start?: number; end?: number }): Promise<QobuzByteRange>;
  release(playbackId: string): void;
  resolveStream(match: QobuzMatch, quality: QobuzQuality): Promise<QobuzPlaybackSource>;
};
export function createQobuzReporter(options: { client: QobuzClient; logger?: QobuzLogger }): {
  close(): Promise<void>;
  ended(playbackId: string, positionSeconds?: number): Promise<void>;
  started(playbackId: string, source: Record<string, any>, positionSeconds?: number): Promise<void>;
};

export function canonicalTrack(input?: CanonicalTrackInput): CanonicalTrack;
export function normalizeQobuzQuality(value: unknown): QobuzQuality;
export function normalizeQobuzAlbumQuality(raw: unknown, fallbackId?: string): QobuzAlbumQuality;
export function normalizeQobuzTrackQuality(raw: unknown, fallbackId?: number | string): QobuzTrackQuality;

export const QOBUZ_AUDIO_QUALITIES: readonly QobuzQuality[];
export const QOBUZ_BASE_URL: string;
export const QOBUZ_USER_AGENT: string;
export const QOBUZ_FORMAT_IDS: Readonly<Record<QobuzQuality, number>>;
