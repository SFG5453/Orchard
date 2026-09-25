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

package dev.sfg.orchard.mobile.playback.smart

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

/**
 * Process memory backed by a versioned SQLite cache for whole-track Best Mix evidence.
 *
 * The database is deliberately below both [BestMixSorter] and [TrackAnalyzer]. A structural pass
 * performed for playback is the same evidence a later Best Mix needs, and keeping separate caches
 * made a downloaded, already-analysed song get decoded again after every process restart.
 *
 * Calls are synchronous and must be made from a worker/IO thread. Failures only disable
 * persistence for that operation; analysis and playback remain optional enhancements.
 */
class BestMixFeatureStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val memory = object : LinkedHashMap<String, TrackFeatures.Features>(
        MAX_MEMORY_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TrackFeatures.Features>?,
        ): Boolean = size > MAX_MEMORY_ENTRIES
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_FEATURES (
                track_id TEXT PRIMARY KEY NOT NULL,
                analysis_version INTEGER NOT NULL,
                analysis_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This is derived, reproducible data. A schema migration must never endanger user data or
        // block playback; rebuilding the cache is the safe upgrade path.
        database.execSQL("DROP TABLE IF EXISTS $TABLE_FEATURES")
        onCreate(database)
    }

    fun load(trackIds: Collection<String>): Map<String, TrackFeatures.Features> {
        val wanted = trackIds.asSequence().filter(String::isNotBlank).distinct().toList()
        if (wanted.isEmpty()) return emptyMap()

        val found = LinkedHashMap<String, TrackFeatures.Features>(wanted.size)
        val missing = ArrayList<String>()
        wanted.forEach { id ->
            synchronized(memory) { memory[id] }?.let { found[id] = it } ?: missing.add(id)
        }
        if (missing.isEmpty()) return found

        runCatching {
            // Android SQLite has a bounded bind-argument count. Collections can be much larger
            // than a normal Best Mix queue, so keep each lookup comfortably below that limit.
            missing.chunked(MAX_QUERY_IDS).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val arguments = chunk.toTypedArray() + TrackFeatures.ANALYSIS_VERSION.toString()
                readableDatabase.query(
                    TABLE_FEATURES,
                    arrayOf("track_id", "analysis_json"),
                    "track_id IN ($placeholders) AND analysis_version = ?",
                    arguments,
                    null,
                    null,
                    null,
                ).use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow("track_id")
                    val jsonColumn = cursor.getColumnIndexOrThrow("analysis_json")
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idColumn)
                        val features = TrackFeatures.parse(JSONObject(cursor.getString(jsonColumn)))
                        if (features.bpm > 0.0 || features.key.isNotBlank()) {
                            synchronized(memory) { memory[id] = features }
                            found[id] = features
                        }
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not read persistent Best Mix analysis", error)
        }
        return found
    }

    fun put(trackId: String, features: TrackFeatures.Features) = putAll(mapOf(trackId to features))

    fun putAll(featuresByTrack: Map<String, TrackFeatures.Features>) {
        val valid = featuresByTrack.filterKeys(String::isNotBlank)
        if (valid.isEmpty()) return
        synchronized(memory) { memory.putAll(valid) }

        runCatching {
            val database = writableDatabase
            database.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                valid.forEach { (trackId, features) ->
                    val values = ContentValues(4).apply {
                        put("track_id", trackId)
                        put("analysis_version", TrackFeatures.ANALYSIS_VERSION)
                        put("analysis_json", TrackFeatures.toJson(features).toString())
                        put("updated_at_ms", now)
                    }
                    database.insertWithOnConflict(
                        TABLE_FEATURES,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not persist Best Mix analysis", error)
        }
    }

    companion object {
        private const val TAG = "OrchardBestMixStore"
        internal const val DATABASE_NAME = "best-mix-analysis.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_FEATURES = "track_features"
        private const val MAX_QUERY_IDS = 400
        private const val MAX_MEMORY_ENTRIES = 64
    }
}
