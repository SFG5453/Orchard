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

export function createQobuzReporter({ client, logger = console } = {}) {
  const active = new Map();

  async function started(playbackId, source, position = 0) {
    if (!source || active.has(playbackId)) return;
    const now = new Date();
    const report = {
      blob: String(source.blob || ''),
      durationSeconds: Number(source.durationSeconds || 0),
      formatId: Number(source.formatId),
      position: Math.max(0, Number(position) || 0),
      startStream: now.toISOString(),
      startedAtUnix: Math.floor(now.getTime() / 1000),
      trackContextUuid: source.trackContextUuid,
      trackId: source.trackId
    };
    active.set(playbackId, report);
    try {
      await client.reportStreamingStart(report);
    } catch (error) {
      logger.warn?.(`Qobuz streaming-start report failed: ${error.message}`);
    }
  }

  async function ended(playbackId, position) {
    const report = active.get(playbackId);
    if (!report) return;
    active.delete(playbackId);
    const endPosition = Math.max(report.position, Number(position) || 0);
    const duration = Math.min(
      report.durationSeconds || Number.MAX_SAFE_INTEGER,
      Math.max(0, Math.floor(endPosition - report.position))
    );
    if (!report.blob || duration < 1) return;
    try {
      await client.reportStreamingEnd({
        blob: report.blob,
        track_context_uuid: report.trackContextUuid,
        start_stream: report.startStream,
        online: true,
        local: false,
        duration
      });
    } catch (error) {
      logger.warn?.(`Qobuz streaming-end report failed: ${error.message}`);
    }
  }

  async function close() {
    const now = Date.now() / 1000;
    await Promise.allSettled([...active].map(([playbackId, report]) =>
      ended(playbackId, report.position + Math.max(0, now - report.startedAtUnix))
    ));
  }

  return { close, ended, started };
}
