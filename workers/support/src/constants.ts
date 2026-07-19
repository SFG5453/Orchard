import type { ReportStatus, ReportType } from './types';

export const REPORT_TYPE_LABELS = {
  bug: 'Bug',
  feature: 'Feature',
  feedback: 'Feedback',
  artist_page: 'Artist page'
} satisfies Record<ReportType, string>;

export const REPORT_TYPES = new Set<ReportType>(['bug', 'feature', 'feedback', 'artist_page']);
export const REPORT_STATUSES = new Set<ReportStatus>([
  'open',
  'waiting_on_user',
  'fixed',
  'resolved',
  'duplicate',
  'unable_to_reproduce',
  'declined',
  'closed'
]);

export const CLOSED_STATUSES = new Set<ReportStatus>([
  'fixed',
  'resolved',
  'duplicate',
  'unable_to_reproduce',
  'declined',
  'closed'
]);

export const MAX_BODY_LENGTH = 12_000;
export const MAX_TITLE_LENGTH = 140;
export const MAX_DIAGNOSTICS_LENGTH = 64_000;
export const MAX_SCREENSHOT_BYTES = 5 * 1024 * 1024;
export const RETENTION_DAYS = 365;

export const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

export const RATE_LIMITS = {
  client: { count: 8, seconds: 60 * 60 },
  report: { count: 12, seconds: 60 * 60 },
  reply: { count: 40, seconds: 60 * 60 },
  screenshot: { count: 120, seconds: 60 * 60 }
} as const;
