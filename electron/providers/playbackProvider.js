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
 * Coordinates optional audio providers without teaching Orchard's YouTube
 * resolver about any provider-specific authentication or stream format.
 *
 * A provider implements this shape:
 *   id: string
 *   enabled(): Promise<boolean> | boolean
 *   matchTrack(track: CanonicalTrack): Promise<ProviderMatch | null>
 *   resolveStream(match: ProviderMatch, quality: AudioQuality): Promise<PlaybackSource>
 */
export function createPlaybackProviderCoordinator({ providers = [] } = {}) {
  const available = providers.filter((provider) => provider?.id);

  async function resolve(track) {
    for (const provider of available) {
      try {
        if (!await provider.enabled()) continue;
        const match = await provider.matchTrack(track);
        if (!match) continue;
        const source = await provider.resolveStream(match, await provider.quality());
        if (!source) continue;
        return { provider: provider.id, match, source };
      } catch (error) {
        // Optional providers must never make the base YouTube playback path
        // unavailable. Authentication and private-API changes are reported in
        // settings while the requested song falls through normally.
        console.warn(`${provider.id} playback resolve failed; using YouTube: ${error.message}`);
        provider.noteError?.(error);
      }
    }
    return null;
  }

  async function playbackStarted(providerId, playbackId, position = 0) {
    return available.find((provider) => provider.id === providerId)
      ?.playbackStarted?.(playbackId, position);
  }

  async function playbackEnded(providerId, playbackId, position = 0) {
    return available.find((provider) => provider.id === providerId)
      ?.playbackEnded?.(playbackId, position);
  }

  async function proxyStream(providerId, playbackId, request, response) {
    const provider = available.find((candidate) => candidate.id === providerId);
    if (!provider?.proxyStream) throw new Error(`Unknown playback provider: ${providerId}`);
    return provider.proxyStream(playbackId, request, response);
  }

  async function close() {
    await Promise.allSettled(available.map((provider) => provider.close?.()));
  }

  return { close, playbackEnded, playbackStarted, proxyStream, resolve };
}
