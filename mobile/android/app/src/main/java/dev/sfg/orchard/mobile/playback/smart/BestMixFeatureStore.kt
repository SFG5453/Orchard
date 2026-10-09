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

package dev.sfg.orchard.mobile.playback.smart

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

/**
 * Best Mix's feature cache, row for row the desktop's `best-mix-v2/features.sqlite`: full head and
 * tail edges for planning plus the compact summaries the shortlist reads, all as the shared Rust
 * analysis returned them.
 *
 * Calls are synchronous and must be made off the main thread. Failures only disable persistence.
 */
class BestMixFeatureStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE features (id TEXT PRIMARY KEY, duration REAL NOT NULL, head TEXT NOT NULL, " +
                "tail TEXT NOT NULL, head_summary TEXT NOT NULL, tail_summary TEXT NOT NULL, " +
                "used_at INTEGER NOT NULL)",
        )
    }

    // Derived, reproducible data: rebuilding is the safe upgrade, as desktop's version bump is.
    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        database.execSQL("DROP TABLE IF EXISTS track_features")
        database.execSQL("DROP TABLE IF EXISTS features")
        onCreate(database)
    }

    /** True when a row for [id] matches [duration] within a second; touches it for the trim. */
    fun has(id: String, duration: Double): Boolean = runCatching {
        val found = readableDatabase.rawQuery(
            "SELECT 1 FROM features WHERE id=? AND ABS(duration-?)<=1 LIMIT 1",
            arrayOf(id, duration.toString()),
        ).use { it.moveToFirst() }
        if (found) {
            writableDatabase.execSQL("UPDATE features SET used_at=? WHERE id=?",
                arrayOf<Any>(System.currentTimeMillis() / 1000, id))
        }
        found
    }.getOrDefault(false)

    /** Stores a `bestMixAnalyze` result. False when it lacks an edge or a summary. */
    fun store(id: String, duration: Double, features: JSONObject): Boolean = runCatching {
        val parts = listOf("head", "tail", "headSummary", "tailSummary")
            .mapNotNull { key -> features.optJSONObject(key)?.takeIf { it.length() > 0 } }
        if (parts.size != 4) return false
        val values = ContentValues().apply {
            put("id", id)
            put("duration", duration)
            put("head", parts[0].toString())
            put("tail", parts[1].toString())
            put("head_summary", parts[2].toString())
            put("tail_summary", parts[3].toString())
            put("used_at", System.currentTimeMillis() / 1000)
        }
        writableDatabase.insertWithOnConflict("features", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0
    }.onFailure { Log.w(TAG, "Could not persist Best Mix analysis", it) }.getOrDefault(false)

    /** `{"head","tail"}` summaries for [id], or an empty object, as desktop's `summary()`. */
    fun summary(id: String): JSONObject = runCatching {
        readableDatabase.rawQuery("SELECT head_summary,tail_summary FROM features WHERE id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) JSONObject()
            else JSONObject().put("head", JSONObject(it.getString(0))).put("tail", JSONObject(it.getString(1)))
        }
    }.getOrDefault(JSONObject())

    /** The full edge for planning, or null. */
    fun edge(id: String, tail: Boolean): JSONObject? = runCatching {
        readableDatabase.rawQuery("SELECT ${if (tail) "tail" else "head"} FROM features WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }
    }.getOrNull()

    /** Desktop's 32 MB cap, oldest rows first. */
    fun trim() = runCatching {
        val database = writableDatabase
        var bytes = database.rawQuery(
            "SELECT COALESCE(SUM(length(head)+length(tail)+length(head_summary)+length(tail_summary)),0) FROM features",
            null,
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        while (bytes > MAX_BYTES) {
            val oldest = database.rawQuery(
                "SELECT id,length(head)+length(tail)+length(head_summary)+length(tail_summary) " +
                    "FROM features ORDER BY used_at LIMIT 1", null,
            ).use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null } ?: break
            database.delete("features", "id=?", arrayOf(oldest.first))
            bytes -= oldest.second
        }
    }

    companion object {
        private const val TAG = "OrchardBestMixStore"
        internal const val DATABASE_NAME = "best-mix-analysis.db"
        // Version 3: saver-quality audio, the encode desktop analyzes for its feature version 2.
        private const val DATABASE_VERSION = 3
        private const val MAX_BYTES = 32L * 1024 * 1024
    }
}
