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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { hashSecret } from './crypto.js';
import { HttpError, json, nowSeconds } from './http.js';
import { readTranslationQuota, translationContext } from './translation_quota.js';

import { TARGET_LANGUAGES, translationMask } from './translation_language.js';
import { inferSong, translationFailure } from './translation_inference.js';
export { TRANSLATION_MODEL } from './translation_inference.js';
export const TRANSLATION_REVISION = 'gemma4-target-v2';
const MAX_BODY_BYTES = 48 * 1024;
const CACHE_SECONDS = 90 * 86400;
const LEASE_SECONDS = 180;
async function readSong(request) {
  const reader = request.body?.getReader();
  if (!reader) throw new HttpError(400, 'invalid_request', 'Expected song_id and lines');
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) {
        await reader.cancel();
        throw new HttpError(413, 'lyrics_too_large', 'Lyrics are too large');
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let body;
  try { body = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { throw new HttpError(400, 'invalid_request', 'Expected song_id and lines'); }
  if (!body || typeof body.song_id !== 'string' || !body.song_id.trim() ||
      body.song_id.length > 256 || /[\u0000-\u001f\u007f]/u.test(body.song_id) ||
      !Array.isArray(body.lines) || !body.lines.length || body.lines.length > 200 ||
      body.lines.some(line => typeof line !== 'string' || !line.trim() || new TextEncoder().encode(line).length > 1024)) {
    throw new HttpError(400, 'invalid_request', 'Send a song ID and 1–200 nonempty lyric lines');
  }
  const lines = body.lines.map(line => line.trim());
  if (new TextEncoder().encode(JSON.stringify(lines)).length > 24 * 1024) {
    throw new HttpError(413, 'lyrics_too_large', 'Lyrics are too large');
  }
  const target = body.target_language === undefined ? 'eng' : body.target_language;
  if (typeof target !== 'string' || !Object.hasOwn(TARGET_LANGUAGES, target)) {
    throw new HttpError(400, 'invalid_target_language', 'Choose English, Spanish, Hindi, Korean, Japanese, or French');
  }
  return { songId: body.song_id.trim(), lines, target };
}

function retryError(status, code, message, seconds) {
  const error = new HttpError(status, code, message);
  error.headers = { 'retry-after': String(seconds) };
  return error;
}

export async function translateSong(request, env) {
  const context = await translationContext(request, env);
  try {
    const result = await translateAuthorizedSong(request, env, context);
    return json({ ...result, quota: await readTranslationQuota(env, context) });
  } catch (error) {
    if (!(error instanceof HttpError)) throw error;
    return json({ error: error.code, error_description: error.message,
      quota: await readTranslationQuota(env, context) }, error.status, error.headers);
  }
}

async function translateAuthorizedSong(request, env, { accountId, ipHash }) {
  const { songId, lines, target } = await readSong(request);
  const mask = translationMask(lines, target);
  if (!mask.some(Boolean)) return { song_id: songId, target_language: target, lines, cached: false };
  const lyricsHash = await hashSecret(JSON.stringify(lines));
  const key = [songId, `${TRANSLATION_REVISION}-${target}`, lyricsHash];
  const now = nowSeconds();
  const day = Math.floor(now / 86400);
  const resetSeconds = (day + 1) * 86400 - now;
  const cached = await env.DB.prepare(
    'SELECT translations, expires_at FROM lyric_translations WHERE song_id = ? AND revision = ? AND lyrics_hash = ?',
  ).bind(...key).first();
  if (cached?.expires_at > now) {
    if (cached.translations) return { song_id: songId, target_language: target, lines: JSON.parse(cached.translations), cached: true };
    throw retryError(409, 'translation_pending', 'This song is being translated. Try again shortly.', 5);
  }
  if (!env.AI) throw new HttpError(503, 'translation_unavailable', 'Account translation is unavailable');
  const lease = crypto.randomUUID();
  try {
    await env.DB.prepare('DELETE FROM lyric_translations WHERE song_id = ? AND revision = ? AND lyrics_hash = ? AND expires_at <= ?')
      .bind(...key, now).run();
    const result = await env.DB.prepare(
      `INSERT INTO lyric_translations (song_id, revision, lyrics_hash, lease_token, expires_at, account_id, ip_hash, day)
       SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS
       (SELECT 1 FROM lyric_translations WHERE song_id = ? AND revision = ? AND lyrics_hash = ?)`,
    ).bind(...key, lease, now + LEASE_SECONDS, accountId, ipHash, day, ...key).run();
    if (!result.meta?.changes) throw retryError(409, 'translation_pending', 'This song is being translated. Try again shortly.', 5);
  } catch (error) {
    if (error.message?.includes('translation_daily_limit')) {
      throw retryError(429, 'translation_daily_limit', 'The daily translation limit has been reached. Resets at 00:00 UTC.', resetSeconds);
    }
    throw error;
  }
  const startedAt = Date.now();
  console.log(JSON.stringify({ event: 'translation.started', request_id: lease, song_id: songId, lines: lines.length }));
  try {
    const output = await inferSong(env.AI, lines.filter((_, index) => mask[index]), target);
    let index = 0;
    const translated = lines.map((line, i) => mask[i] ? output[index++] : line);
    await env.DB.prepare(`UPDATE lyric_translations SET translations = ?, expires_at = ?, account_id = NULL, ip_hash = NULL
      WHERE song_id = ? AND revision = ? AND lyrics_hash = ? AND lease_token = ?`)
      .bind(JSON.stringify(translated), nowSeconds() + CACHE_SECONDS, ...key, lease).run();
    console.log(JSON.stringify({ event: 'translation.completed', request_id: lease, elapsed_ms: Date.now() - startedAt }));
    return { song_id: songId, target_language: target, lines: translated, cached: false };
  } catch (error) {
    const { failure, upstreamCode } = translationFailure(error);
    console.error(JSON.stringify({ event: 'translation.failed', request_id: lease, elapsed_ms: Date.now() - startedAt,
      code: failure.code, upstream_code: upstreamCode }));
    await env.DB.prepare('DELETE FROM lyric_translations WHERE song_id = ? AND revision = ? AND lyrics_hash = ? AND lease_token = ?')
      .bind(...key, lease).run();
    // Failed generation still consumes admission: retries cannot buy unbounded inference.
    throw failure;
  }
}

export async function purgeTranslations(env, now) {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM lyric_translations WHERE expires_at <= ?').bind(now),
    env.DB.prepare('DELETE FROM translation_daily_usage WHERE day < ?').bind(Math.floor(now / 86400) - 1),
  ]);
}
