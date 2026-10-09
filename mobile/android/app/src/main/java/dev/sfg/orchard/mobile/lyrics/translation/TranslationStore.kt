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

package dev.sfg.orchard.mobile.lyrics.translation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/**
 * Finished lines keyed by sha1(revision + "\n" + text), as on desktop, so a new model revision never
 * reuses an old one's output. Lives beside the packs because "Clear cache" wipes cacheDir under an
 * open database.
 */
internal class TranslationStore(context: Context) :
    SQLiteOpenHelper(context, java.io.File(context.noBackupFilesDir, "lyric-translations.sqlite").path, null, 1) {
    private var writes = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE lines (key TEXT PRIMARY KEY, translation TEXT NOT NULL, used INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX lines_used ON lines (used)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun lookup(revision: String, texts: Collection<String>): Map<String, String> {
        if (texts.isEmpty()) return emptyMap()
        val byKey = texts.associateBy { key(revision, it) }
        val found = HashMap<String, String>()
        val db = readableDatabase
        // SQLite caps bound parameters; songs rarely need more than one chunk.
        byKey.keys.chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT key, translation FROM lines WHERE key IN ($marks)", chunk.toTypedArray()).use {
                while (it.moveToNext()) found[byKey.getValue(it.getString(0))] = it.getString(1)
            }
        }
        return found
    }

    @Synchronized
    fun store(revision: String, text: String, translation: String) {
        val db = writableDatabase
        db.insertWithOnConflict(
            "lines",
            null,
            ContentValues().apply {
                put("key", key(revision, text))
                put("translation", translation)
                put("used", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        // Pruned now and then; 50k lines is a few thousand songs and a few megabytes.
        if (++writes % 200 == 0) {
            db.execSQL(
                "DELETE FROM lines WHERE key IN (SELECT key FROM lines ORDER BY used DESC LIMIT -1 OFFSET $MAX_LINES)",
            )
        }
    }

    private fun key(revision: String, text: String): String =
        MessageDigest.getInstance("SHA-1").digest("$revision\n$text".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_LINES = 50_000
    }
}
