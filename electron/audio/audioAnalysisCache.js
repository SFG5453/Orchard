/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import { access, mkdir, readFile, rename } from 'node:fs/promises';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { finalizeTrackAnalysis } from '../../shared/trackAnalysis.js';

const STORAGE_FORMAT = 1;
const DEFAULT_HOT_ITEMS = 12;
const ACCESS_WRITE_INTERVAL_MS = 60_000;
const DERIVED_FIELDS = new Set([
  'audibleRange',
  'timing',
  'harmonic',
  'frames',
  'boundaries'
]);
const LEGACY_CURVE_FIELDS = [
  'energyCurve',
  'lowEnergyCurve',
  'midEnergyCurve',
  'highEnergyCurve',
  'vocalActivityMask'
];

function compactAnalysis(result) {
  const compact = {};
  const hasFeatureFrames = Array.isArray(result?.transitionFeatureFrames) &&
    result.transitionFeatureFrames.length > 0;
  for (const [key, value] of Object.entries(result || {})) {
    if (DERIVED_FIELDS.has(key)) continue;
    if (hasFeatureFrames && LEGACY_CURVE_FIELDS.includes(key)) continue;
    compact[key] = value;
  }
  return compact;
}

function restoreLegacyCurves(result) {
  const frames = Array.isArray(result?.transitionFeatureFrames)
    ? result.transitionFeatureFrames
    : [];
  if (!frames.length || LEGACY_CURVE_FIELDS.every((field) => field in result)) return result;
  return {
    ...result,
    energyCurve: result.energyCurve ?? frames.map((frame) => ({ time: frame.time, energy: frame.energy })),
    lowEnergyCurve: result.lowEnergyCurve ?? frames.map((frame) => ({ time: frame.time, energy: frame.low })),
    midEnergyCurve: result.midEnergyCurve ?? frames.map((frame) => ({ time: frame.time, energy: frame.mid })),
    highEnergyCurve: result.highEnergyCurve ?? frames.map((frame) => ({ time: frame.time, energy: frame.high })),
    vocalActivityMask: result.vocalActivityMask ?? frames.map((frame) => frame.vocal)
  };
}

function expandAnalysis(result) {
  if (!result || typeof result !== 'object') return null;
  return finalizeTrackAnalysis(restoreLegacyCurves(result));
}

async function unusedBackupPath(legacyPath, version) {
  const base = `${legacyPath}.migrated-v${version}`;
  try {
    await access(base);
  } catch {
    return base;
  }
  return `${base}-${Date.now()}`;
}

/**
 * SQLite-backed analysis cache. Only a small hot LRU is materialized in V8;
 * all other tracks stay as compact JSON rows until requested.
 */
export function createAudioAnalysisCache({
  databasePath,
  legacyPath,
  version,
  maxItems,
  cleanTrackId,
  validate,
  log = () => {},
  hotItems = DEFAULT_HOT_ITEMS
}) {
  const maximumItems = Math.max(1, Math.floor(Number(maxItems) || 1));
  const maximumHotItems = Math.max(1, Math.floor(Number(hotItems) || DEFAULT_HOT_ITEMS));
  const hot = new Map();
  let database = null;
  let statements = null;

  function remember(trackId, result, lastAccess = Date.now()) {
    hot.delete(trackId);
    hot.set(trackId, { result, lastAccess });
    while (hot.size > maximumHotItems) hot.delete(hot.keys().next().value);
  }

  function prepareDatabase() {
    database = new DatabaseSync(databasePath);
    database.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = NORMAL;
      CREATE TABLE IF NOT EXISTS audio_analysis (
        track_id TEXT PRIMARY KEY,
        version INTEGER NOT NULL,
        storage_format INTEGER NOT NULL,
        last_used INTEGER NOT NULL,
        result_json TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS audio_analysis_last_used
        ON audio_analysis(last_used);
      CREATE TABLE IF NOT EXISTS cache_metadata (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
      );
    `);
    statements = {
      get: database.prepare(`
        SELECT last_used, result_json
        FROM audio_analysis
        WHERE track_id = ? AND version = ?
      `),
      put: database.prepare(`
        INSERT INTO audio_analysis(track_id, version, storage_format, last_used, result_json)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(track_id) DO UPDATE SET
          version = excluded.version,
          storage_format = excluded.storage_format,
          last_used = excluded.last_used,
          result_json = excluded.result_json
      `),
      touch: database.prepare('UPDATE audio_analysis SET last_used = ? WHERE track_id = ?'),
      remove: database.prepare('DELETE FROM audio_analysis WHERE track_id = ?'),
      removeOtherVersions: database.prepare('DELETE FROM audio_analysis WHERE version != ?'),
      prune: database.prepare(`
        DELETE FROM audio_analysis
        WHERE track_id IN (
          SELECT track_id FROM audio_analysis
          ORDER BY last_used DESC, track_id DESC
          LIMIT -1 OFFSET ${maximumItems}
        )
      `),
      metadata: database.prepare('SELECT value FROM cache_metadata WHERE key = ?'),
      setMetadata: database.prepare(`
        INSERT INTO cache_metadata(key, value) VALUES (?, ?)
        ON CONFLICT(key) DO UPDATE SET value = excluded.value
      `)
    };
    statements.removeOtherVersions.run(version);
    statements.prune.run();
  }

  async function migrateLegacyJson() {
    if (!legacyPath || path.resolve(legacyPath) === path.resolve(databasePath)) return;
    const migrationKey = `legacy-json-v${version}`;
    if (statements.metadata.get(migrationKey)) return;

    let stored;
    try {
      stored = JSON.parse(await readFile(legacyPath, 'utf8'));
    } catch (error) {
      if (error?.code !== 'ENOENT') {
        log('cache-migration-failed', { errorMessage: String(error?.message || error).slice(0, 500) });
      }
      return;
    }

    let imported = 0;
    database.exec('BEGIN IMMEDIATE');
    try {
      if (stored?.version === version && Array.isArray(stored.items)) {
        for (const item of stored.items.slice(-maximumItems)) {
          const trackId = cleanTrackId(item?.trackId);
          if (!trackId || !validate(item?.result)) continue;
          statements.put.run(
            trackId,
            version,
            STORAGE_FORMAT,
            Number(item.lastUsed) || 0,
            JSON.stringify(compactAnalysis(item.result))
          );
          imported += 1;
        }
      }
      statements.prune.run();
      statements.setMetadata.run(migrationKey, JSON.stringify({ imported, migratedAt: Date.now() }));
      database.exec('COMMIT');
      database.exec('PRAGMA wal_checkpoint(TRUNCATE)');
    } catch (error) {
      database.exec('ROLLBACK');
      throw error;
    }

    try {
      await rename(legacyPath, await unusedBackupPath(legacyPath, version));
    } catch (error) {
      log('cache-migration-backup-failed', { errorMessage: String(error?.message || error).slice(0, 500) });
    }
    log('cache-migration-ready', { imported });
  }

  const ready = (async () => {
    await mkdir(path.dirname(databasePath), { recursive: true });
    prepareDatabase();
    await migrateLegacyJson();
  })().catch((error) => {
    log('cache-database-failed', { errorMessage: String(error?.message || error).slice(0, 500) });
    try {
      database?.close();
    } catch {}
    database = null;
    statements = null;
  });

  function get(trackId) {
    const memoryEntry = hot.get(trackId);
    const now = Date.now();
    if (memoryEntry) {
      hot.delete(trackId);
      hot.set(trackId, memoryEntry);
      if (database && now - memoryEntry.lastAccess >= ACCESS_WRITE_INTERVAL_MS) {
        statements.touch.run(now, trackId);
        memoryEntry.lastAccess = now;
      }
      return memoryEntry.result;
    }
    if (!database) return null;

    const row = statements.get.get(trackId, version);
    if (!row) return null;
    try {
      const result = expandAnalysis(JSON.parse(row.result_json));
      if (!validate(result)) throw new Error('Invalid cached analysis');
      statements.touch.run(now, trackId);
      remember(trackId, result, now);
      return result;
    } catch {
      statements.remove.run(trackId);
      return null;
    }
  }

  function set(trackId, result, lastUsed = Date.now()) {
    remember(trackId, result, lastUsed);
    if (!database) return;
    statements.put.run(
      trackId,
      version,
      STORAGE_FORMAT,
      lastUsed,
      JSON.stringify(compactAnalysis(result))
    );
    statements.prune.run();
  }

  function close() {
    hot.clear();
    statements = null;
    database?.close();
    database = null;
  }

  return { ready, get, set, close };
}

export const audioAnalysisCacheInternals = { compactAnalysis, expandAnalysis };
