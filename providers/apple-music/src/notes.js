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

// Apple editorial notes are HTML; keep the subset Qt StyledText renders.
const KEEP = new Set(['b', 'i', 'u', 'br']);
const ALIAS = { strong: 'b', em: 'i' };

export function cleanNotes(value) {
  return String(value || '')
    .replace(/\r\n?/g, '\n')
    .replace(/<\/(?:p|div|li)>/gi, '<br><br>')
    .replace(/<\/?([a-z][a-z0-9]*)\b[^>]*>/gi, (tag, name) => {
      const lower = name.toLowerCase();
      const kept = KEEP.has(lower) ? lower : ALIAS[lower];
      if (!kept) return '';
      return kept === 'br' ? '<br>' : tag.startsWith('</') ? `</${kept}>` : `<${kept}>`;
    })
    .replace(/\n/g, '<br>')
    .replace(/(?:<br>){3,}/g, '<br><br>')
    .replace(/^(?:<br>)+|(?:<br>)+$/g, '')
    .trim();
}

const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Wraps known names (name -> browse id) in links; text inside tags is left alone. */
export function linkNotes(html, targets) {
  const names = [...targets.keys()].filter((name) => name.length > 2).sort((a, b) => b.length - a.length);
  if (!html || !names.length) return html;
  const pattern = new RegExp(`(?<![\\p{L}\\p{N}])(${names.map(escapeRegExp).join('|')})(?![\\p{L}\\p{N}])`, 'gu');
  return html
    .split(/(<[^>]+>)/)
    .map((part) => (part.startsWith('<') ? part : part.replace(pattern, (name) => `<a href="${targets.get(name)}">${name}</a>`)))
    .join('');
}

/** Link targets for the artists and albums that tracks and album or artist cards name. */
export function noteTargets(tracks, extra = []) {
  const targets = new Map();
  for (const entry of [...extra, ...tracks]) {
    if (['album', 'artist'].includes(entry.type) && entry.title && entry.browseId && !targets.has(entry.title)) {
      targets.set(entry.title, entry.browseId);
    }
    (entry.artists || []).forEach((name, index) => {
      const id = (entry.artistBrowseIds || [])[index];
      if (name && id && !targets.has(name)) targets.set(name, id);
    });
    if (entry.album && entry.albumId && !targets.has(entry.album)) targets.set(entry.album, entry.albumId);
  }
  return targets;
}
