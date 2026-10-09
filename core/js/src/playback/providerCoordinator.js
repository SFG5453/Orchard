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

/**
 * Coordinates optional playback providers without coupling the queue to any
 * provider protocol or transport implementation.
 */
export function createPlaybackProviderCoordinator({ providers = [], logger = console } = {}) {
  const available = providers.filter((provider) => provider?.id);

  function provider(providerId) {
    return available.find((candidate) => candidate.id === providerId);
  }

  async function resolve(track) {
    for (const candidate of available) {
      try {
        if (!await candidate.enabled()) continue;
        const match = await candidate.matchTrack(track);
        if (!match) continue;
        const source = await candidate.resolveStream(match, await candidate.quality());
        if (source) return { provider: candidate.id, match, source };
      } catch (error) {
        logger.warn?.(`${candidate.id} playback resolve failed; using the next provider: ${error.message}`);
        candidate.noteError?.(error);
      }
    }
    return null;
  }

  async function playbackStarted(providerId, playbackId, position = 0) {
    return provider(providerId)?.playbackStarted?.(playbackId, position);
  }

  async function playbackEnded(providerId, playbackId, position = 0) {
    return provider(providerId)?.playbackEnded?.(playbackId, position);
  }

  async function readRange(providerId, playbackId, range) {
    const selected = provider(providerId);
    if (!selected?.readRange) throw new Error(`Unknown playback provider: ${providerId}`);
    return selected.readRange(playbackId, range);
  }

  function release(providerId, playbackId) {
    provider(providerId)?.release?.(playbackId);
  }

  async function close() {
    await Promise.allSettled(available.map((candidate) => candidate.close?.()));
  }

  return { close, playbackEnded, playbackStarted, readRange, release, resolve };
}

