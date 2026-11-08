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

// Reads artist, title and count fields from browse page headers.
export function createBrowseHeaders({ cleanedText, textParts }) {
  function artistRunBrowseId(run) {
    const browseId = run?.navigationEndpoint?.browseEndpoint?.browseId || run?.endpoint?.payload?.browseId || '';
    return browseId.startsWith('UC') ? browseId : '';
  }
  function artistRunText(runs = []) {
    return runs.filter(artistRunBrowseId).map((run) => run.text).filter(Boolean).join(', ');
  }
  // Album rows carry no artist runs; the header link is the only artist id.
  function browseHeaderArtistId(header) {
    const runs = [
      ...(header?.strapline_text_one?.runs || header?.straplineTextOne?.runs || []),
      ...(header?.subtitle?.runs || [])
    ];
    return header?.author?.channel_id || runs.map(artistRunBrowseId).find(Boolean) || '';
  }
  function browseHeaderArtist(header) {
    return cleanedText(header?.author?.name) ||
      artistRunText(header?.strapline_text_one?.runs || header?.straplineTextOne?.runs) ||
      artistRunText(header?.subtitle?.runs) ||
      cleanedText(header?.facepile?.avatarStackViewModel?.text?.content) ||
      '';
  }
  function browseHeaderTitle(header) {
    return cleanedText(header?.title || header?.header?.title) || '';
  }
  function browseHeaderDescription(header) {
    return cleanedText(header?.description?.text || header?.description || header?.header?.description || header?.subtitle || header?.header?.subtitle) || '';
  }
  function browseHeaderThumbnail(header) {
    return header?.thumbnail || header?.thumbnails || header?.foreground_thumbnail || header?.background || header?.header?.thumbnail || null;
  }
  function browseHeaderYear(header) {
    return header?.year || textParts(header?.subtitle).find((part) => (/^[12][0-9]{3}$/).test(part)) || '';
  }
  function browseHeaderItemCount(header) {
    return textParts(header?.second_subtitle).find((part) => /\b(song|songs|track|tracks|video|videos)\b/i.test(part)) || '';
  }
  function browseHeaderDuration(header) {
    return textParts(header?.second_subtitle).find((part) => /\b(hour|hours|minute|minutes)\b/i.test(part)) || '';
  }
  // Library bookmark state; null when the response has no signed-in save button.
  function browseHeaderSaved(header) {
    const toggle = (header?.buttons || []).map((button) => button?.toggleButtonRenderer).find((button) =>
      button?.defaultServiceEndpoint?.likeEndpoint || button?.toggledServiceEndpoint?.likeEndpoint);
    return toggle ? Boolean(toggle.isToggled) : null;
  }
  function browseHeaderViews(header) {
    return textParts(header?.second_subtitle).find((part) => /\b(view|views|play|plays)\b/i.test(part)) || '';
  }

  return {
    artistRunBrowseId,
    artistRunText,
    browseHeaderArtistId,
    browseHeaderArtist,
    browseHeaderTitle,
    browseHeaderDescription,
    browseHeaderThumbnail,
    browseHeaderYear,
    browseHeaderItemCount,
    browseHeaderDuration,
    browseHeaderViews,
    browseHeaderSaved
  };
}
