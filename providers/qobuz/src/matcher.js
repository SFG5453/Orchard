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

const VERSION_MARKERS = Object.freeze([
  'acoustic', 'clean', 'edit', 'instrumental', 'karaoke', 'live',
  'mono', 'remaster', 'remastered', 'remix', 'slowed', 'sped up', 'stereo'
]);

export function normalizedQobuzText(value) {
  return String(value || '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/\b(feat|featuring|ft)\.?\s+.+$/i, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

// Ignores a leading "the" and spacing so "Notorious B.I.G." equals "The Notorious BIG".
function artistKey(value) {
  return normalizedQobuzText(value).replace(/^the /, '').replace(/ /g, '');
}

function sharesArtist(targetArtists, candidateArtists) {
  const offered = candidateArtists.map(artistKey);
  return targetArtists.some((artist) => offered.includes(artistKey(artist)));
}

function versionMarkers(value) {
  const normalized = normalizedQobuzText(value);
  return VERSION_MARKERS.filter((marker) => new RegExp(`\\b${marker.replace(' ', '\\s+')}\\b`).test(normalized));
}

function rawArtistNames(raw = {}) {
  const names = [
    raw.performer?.name,
    raw.artist?.name,
    raw.album?.artist?.name,
    ...(Array.isArray(raw.performers)
      ? raw.performers.map((performer) => performer?.name || performer)
      : [])
  ];
  return [...new Set(names.map(normalizedQobuzText).filter(Boolean))];
}

function trackQuality(raw = {}) {
  return Number(raw.maximum_bit_depth || raw.bit_depth || (raw.hires ? 24 : 16)) * 1_000_000 +
    Number(raw.maximum_sampling_rate || raw.sampling_rate || 0) * 1000;
}

function publicMatch(raw, method, confidence) {
  return {
    provider: 'qobuz',
    qobuzTrackId: Number(raw.id),
    method,
    confidence,
    title: String(raw.title || ''),
    artist: String(raw.performer?.name || raw.artist?.name || raw.album?.artist?.name || ''),
    album: String(raw.album?.title || ''),
    durationSeconds: Number(raw.duration || 0),
    isrc: String(raw.isrc || '').toUpperCase(),
    explicit: raw.parental_warning === true,
    hires: raw.hires === true || Number(raw.maximum_bit_depth || 0) > 16,
    bitDepth: Number(raw.maximum_bit_depth || raw.bit_depth || 0) || undefined,
    sampleRate: Number(raw.maximum_sampling_rate || raw.sampling_rate || 0) || undefined
  };
}

function candidatesFromSearch(response = {}) {
  return (response.tracks?.items || [])
    .filter((track) => Number.isSafeInteger(Number(track?.id)) && track.streamable !== false);
}

function sameVersion(target, candidate) {
  const wanted = versionMarkers(`${target.title || ''} ${target.album || ''}`);
  const offered = versionMarkers(`${candidate.title || ''} ${candidate.album?.title || ''}`);
  if (wanted.join('|') !== offered.join('|')) return false;
  return candidate.parental_warning === true ? target.explicit === true : target.explicit !== true;
}

function durationDelta(target, candidate) {
  if (!target.durationMs || !candidate.duration) return Number.POSITIVE_INFINITY;
  return Math.abs(target.durationMs / 1000 - Number(candidate.duration));
}

function metadataScore(target, candidate) {
  if (!sameVersion(target, candidate)) return null;
  const title = normalizedQobuzText(target.title);
  const candidateTitle = normalizedQobuzText(candidate.title);
  if (!title || candidateTitle !== title) return null;

  const targetArtists = target.artists.map(normalizedQobuzText).filter(Boolean);
  const candidateArtists = rawArtistNames(candidate);
  if (!sharesArtist(targetArtists, candidateArtists)) return null;

  const delta = durationDelta(target, candidate);
  if (delta > 3 || !Number.isFinite(delta)) return null;
  const albumExact = target.album && normalizedQobuzText(candidate.album?.title) === normalizedQobuzText(target.album);
  return 94 - delta * 2 + (albumExact ? 3 : 0) + Math.min(2, trackQuality(candidate) / 100_000_000);
}

function albumTitleScore(target, candidate) {
  if (!target.album || !sameVersion(target, candidate)) return null;
  if (normalizedQobuzText(target.title) !== normalizedQobuzText(candidate.title)) return null;
  if (normalizedQobuzText(target.album) !== normalizedQobuzText(candidate.album?.title)) return null;
  const delta = durationDelta(target, candidate);
  if (delta > 3 || !Number.isFinite(delta)) return null;
  const targetArtists = target.artists.map(normalizedQobuzText).filter(Boolean);
  const candidateArtists = rawArtistNames(candidate);
  const strongArtist = sharesArtist(targetArtists, candidateArtists) ||
    targetArtists.some((artist) => candidateArtists.some((offered) =>
      Math.min(artist.length, offered.length) >= 6 && (artist.includes(offered) || offered.includes(artist))
    ));
  return strongArtist ? 88 - delta * 2 + Math.min(2, trackQuality(candidate) / 100_000_000) : null;
}

function durationlessCandidates(target, candidates) {
  const title = normalizedQobuzText(target.title);
  const album = normalizedQobuzText(target.album);
  const artists = target.artists.map(normalizedQobuzText).filter(Boolean);
  return candidates.filter((candidate) =>
    Number(candidate.duration) > 0 &&
    normalizedQobuzText(candidate.title) === title &&
    sharesArtist(artists, rawArtistNames(candidate)) &&
    sameVersion(target, candidate) &&
    (!album || normalizedQobuzText(candidate.album?.title) === album)
  );
}

function sameRecording(left, right) {
  const leftIsrc = String(left.isrc || '').toUpperCase();
  const rightIsrc = String(right.isrc || '').toUpperCase();
  if (leftIsrc && rightIsrc) return leftIsrc === rightIsrc;
  return normalizedQobuzText(left.title) === normalizedQobuzText(right.title) &&
    normalizedQobuzText(left.album?.title) === normalizedQobuzText(right.album?.title) &&
    Math.abs(Number(left.duration || 0) - Number(right.duration || 0)) <= 1 &&
    sharesArtist(rawArtistNames(left), rawArtistNames(right));
}

// Names the furthest check the closest candidate passed, for miss reports.
export function explainQobuzMiss(target, candidates) {
  if (!candidates.length) return { reason: 'search returned no streamable tracks' };
  const title = normalizedQobuzText(target.title);
  const artists = target.artists.map(normalizedQobuzText).filter(Boolean);
  let best = { stage: -1, reason: 'no candidate shares the title' };
  for (const candidate of candidates) {
    const delta = durationDelta(target, candidate);
    const durationReason = target.durationMs <= 0 ? 'source track has no duration' :
      !(Number(candidate.duration) > 0) ? 'Qobuz candidate has no duration' :
        `duration off by ${delta.toFixed(1)}s`;
    const checks = [
      [normalizedQobuzText(candidate.title) === title, 'title differs'],
      [sharesArtist(artists, rawArtistNames(candidate)), 'artist differs'],
      [sameVersion(target, candidate), 'version or explicit flag differs'],
      [delta <= 3, durationReason]
    ];
    let stage = checks.findIndex(([ok]) => !ok);
    if (stage < 0) stage = checks.length;
    if (stage <= best.stage) continue;
    best = {
      stage,
      reason: stage === checks.length ? 'ambiguous between equal recordings' : checks[stage][1],
      candidate: `${candidate.title} / ${rawArtistNames(candidate).join(', ')} / ${candidate.album?.title || ''}`,
      candidateDurationSeconds: Number(candidate.duration) || 0
    };
  }
  return { reason: best.reason, candidate: best.candidate,
    candidateDurationSeconds: best.candidateDurationSeconds };
}

export function selectQobuzMatch(target, candidates, method = 'metadata') {
  if (method === 'isrc') {
    const isrc = String(target.isrc || '').toUpperCase();
    const exact = candidates
      .filter((candidate) => String(candidate.isrc || '').toUpperCase() === isrc)
      .filter((candidate) => sameVersion(target, candidate))
      .sort((a, b) => trackQuality(b) - trackQuality(a));
    return exact[0] ? publicMatch(exact[0], 'isrc', 1) : null;
  }

  const scoreCandidate = method === 'album-title' ? albumTitleScore : metadataScore;
  const ranked = candidates
    .map((candidate) => ({ candidate, score: scoreCandidate(target, candidate) }))
    .filter(({ score }) => score !== null)
    .sort((a, b) => b.score - a.score || trackQuality(b.candidate) - trackQuality(a.candidate));
  if (!ranked.length) {
    if (method !== 'metadata' || target.durationMs > 0) return null;
    // Missing catalog times happen. Exact metadata can still identify a recording;
    // if releases disagree, let YouTube play detective instead.
    const eligible = durationlessCandidates(target, candidates)
      .sort((a, b) => trackQuality(b) - trackQuality(a));
    if (!eligible.length || eligible.some((candidate) => !sameRecording(eligible[0], candidate))) return null;
    return publicMatch(eligible[0], 'metadata', target.album ? 0.9 : 0.8);
  }
  // Two equally plausible recordings are safer as a YouTube fallback.
  if (ranked[1] && Math.abs(ranked[0].score - ranked[1].score) < 0.5 &&
      Number(ranked[0].candidate.id) !== Number(ranked[1].candidate.id) &&
      !sameRecording(ranked[0].candidate, ranked[1].candidate)) return null;
  return publicMatch(ranked[0].candidate, method, Math.min(0.99, ranked[0].score / 100));
}

export function createQobuzMatcher({ search, onMiss, cacheTtlMs = 30 * 60_000 } = {}) {
  const cache = new Map();

  return async function matchTrack(target) {
    if (!target?.title || !target?.artists?.length) return null;
    const cacheKey = JSON.stringify(target);
    const hit = cache.get(cacheKey);
    if (hit?.expiresAt > Date.now()) return hit.match;

    let match = null;
    let candidates = [];
    if (/^[A-Z]{2}[A-Z0-9]{3}\d{7}$/.test(target.isrc || '')) {
      match = selectQobuzMatch(target, candidatesFromSearch(await search(target.isrc)), 'isrc');
    }
    if (!match) {
      const query = `${target.title} ${target.artists[0]} ${target.album || ''}`.trim();
      candidates = candidatesFromSearch(await search(query));
      match = selectQobuzMatch(target, candidates) || selectQobuzMatch(target, candidates, 'album-title');
    }

    if (!match) onMiss?.({ title: target.title, artists: target.artists, durationMs: target.durationMs,
      ...explainQobuzMiss(target, candidates) });
    cache.set(cacheKey, { match, expiresAt: Date.now() + cacheTtlMs });
    while (cache.size > 200) cache.delete(cache.keys().next().value);
    return match;
  };
}
