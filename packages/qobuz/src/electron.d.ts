import type {
  CanonicalTrackInput,
  FetchLike,
  QobuzLogger,
  QobuzMatch,
  QobuzPlaybackSource,
  QobuzQuality
} from './index.js';

export interface QobuzIpcChannels {
  CONNECT: string;
  DISCONNECT: string;
  STATUS: string;
  UPDATE: string;
}

export interface QobuzElectronOptions {
  app: any;
  applicationName?: string;
  BrowserWindow: any;
  ipcChannels: QobuzIpcChannels;
  ipcMain: any;
  logger?: QobuzLogger;
  net: { fetch: FetchLike };
  partition?: string;
  recordPath?: string;
  safeStorage: any;
  session: any;
  softwareVersion?: string;
}

export interface QobuzElectronProvider {
  id: 'qobuz';
  enabled(): Promise<boolean>;
  quality(): Promise<QobuzQuality>;
  matchTrack(track: CanonicalTrackInput): Promise<QobuzMatch | null>;
  resolveStream(match: QobuzMatch, quality?: QobuzQuality): Promise<QobuzPlaybackSource>;
  proxyStream(playbackId: string, request: any, response: any): Promise<void>;
  playbackStarted(playbackId: string, positionSeconds?: number): Promise<void>;
  playbackEnded(playbackId: string, positionSeconds?: number): Promise<void>;
  noteError(error: unknown): void;
  close(): Promise<void>;
}

export function setupQobuzElectron(options: QobuzElectronOptions): QobuzElectronProvider;
export const setupQobuz: typeof setupQobuzElectron;
export function createQobuzAuth(options: Omit<QobuzElectronOptions, 'ipcChannels' | 'ipcMain' | 'net' | 'session'> & {
  bootstrap: { get(options?: { refresh?: boolean }): Promise<any> };
  electronSession: any;
  fetchImpl?: FetchLike;
}): {
  connect(): Promise<any>;
  credentials(): Promise<{ token: string; userId: number } | null>;
  disconnect(): Promise<any>;
  load(): Promise<void>;
  noteError(error: unknown): void;
  publicStatus(): Promise<any>;
  update(settings?: { enabled?: boolean; quality?: QobuzQuality }): Promise<any>;
};
export const QOBUZ_PARTITION: string;
