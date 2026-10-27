/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

package dev.sfg.orchard.mobile.playback.smart

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

/**
 * Adaptive mix's whole-track evidence by track id, row for row desktop's
 * `adaptive-mix/evidence.sqlite`. Playback sends it back with the next mix of the song so the
 * beat model runs once per song; Best Mix plans with its `beatModel` summary.
 *
 * The file opens on the first query. Calls are synchronous and must be made off the main thread;
 * failures only disable persistence.
 */
class MixEvidenceStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE evidence (id TEXT PRIMARY KEY, duration REAL NOT NULL, evidence TEXT NOT NULL, " +
                "beat_model TEXT NOT NULL, used_at INTEGER NOT NULL)",
        )
        database.execSQL("CREATE INDEX evidence_used_at ON evidence(used_at)")
    }

    // Derived data the next mix measures again, as desktop drops rows of another version.
    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = rebuild(database)
    override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = rebuild(database)

    private fun rebuild(database: SQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS evidence")
        onCreate(database)
    }

    /** The native mix's `outgoingMeasured`/`incomingMeasured` for a song, or null. */
    fun evidence(id: String, duration: Double): JSONObject? = read("evidence", id, duration)

    /** Its `beatModel` summary, or null. */
    fun beatModel(id: String, duration: Double): JSONObject? = read("beat_model", id, duration)

    private fun read(column: String, id: String, duration: Double): JSONObject? = runCatching {
        if (id.isEmpty()) return null
        val text = readableDatabase.rawQuery(
            "SELECT $column FROM evidence WHERE id=? AND ABS(duration-?)<=?",
            arrayOf(id, duration.toString(), DURATION_SLACK.toString()),
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        writableDatabase.execSQL("UPDATE evidence SET used_at=? WHERE id=?",
            arrayOf<Any>(System.currentTimeMillis() / 1000, id))
        JSONObject(text)
    }.onFailure { Log.w(TAG, "Could not read adaptive mix evidence", it) }.getOrNull()

    fun store(id: String, duration: Double, measured: JSONObject?) {
        val beatModel = measured?.optJSONObject("beatModel") ?: return
        if (id.isEmpty()) return
        runCatching {
            val values = ContentValues().apply {
                put("id", id)
                put("duration", duration)
                put("evidence", measured.toString())
                put("beat_model", beatModel.toString())
                put("used_at", System.currentTimeMillis() / 1000)
            }
            writableDatabase.insertWithOnConflict("evidence", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            trim()
        }.onFailure { Log.w(TAG, "Could not persist adaptive mix evidence", it) }
    }

    /** Desktop's 32 MB cap, least recently used first. */
    private fun trim() {
        val database = writableDatabase
        var bytes = database.rawQuery(
            "SELECT COALESCE(SUM(length(evidence)+length(beat_model)),0) FROM evidence", null,
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        while (bytes > MAX_BYTES) {
            val oldest = database.rawQuery(
                "SELECT id,length(evidence)+length(beat_model) FROM evidence ORDER BY used_at LIMIT 1", null,
            ).use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null } ?: break
            database.delete("evidence", "id=?", arrayOf(oldest.first))
            bytes -= oldest.second
        }
    }

    companion object {
        private const val TAG = "OrchardMixEvidence"
        internal const val DATABASE_NAME = "adaptive-mix-evidence.db"
        /** The native `measured::VERSION`. */
        private const val DATABASE_VERSION = 1
        private const val MAX_BYTES = 32L * 1024 * 1024
        /** Player and catalog durations of one song disagree by a second or so. */
        private const val DURATION_SLACK = 2.0
    }
}
