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

package dev.sfg.orchard.mobile.audio

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler

/**
 * Whether the phone is driving a car head unit.
 *
 * Android Auto projects over USB and leaves the phone process in its normal UI mode, so neither the
 * audio route nor `UiModeManager` reveals the car. Android Auto instead publishes its state through
 * this content provider, which is what `androidx.car.app`'s CarConnection reads; querying it
 * directly keeps the whole car-app library out of the build for one integer.
 */
internal object CarConnection {
    const val NOT_CONNECTED = 0

    /** Running on Android Automotive OS, where the head unit *is* the device. */
    const val NATIVE = 1

    /** Projecting to a head unit over Android Auto. */
    const val PROJECTION = 2

    val URI: Uri = Uri.parse("content://androidx.car.app.connection/carconnection")
    private const val STATE_COLUMN = "CarConnectionState"

    /** [NOT_CONNECTED] whenever Android Auto is absent or the provider refuses the read. */
    fun state(context: Context): Int = runCatching {
        context.contentResolver.query(URI, arrayOf(STATE_COLUMN), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(STATE_COLUMN)
            if (column < 0 || !cursor.moveToNext()) NOT_CONNECTED else cursor.getInt(column)
        } ?: NOT_CONNECTED
    }.getOrDefault(NOT_CONNECTED)

    fun isConnected(context: Context): Boolean = state(context) != NOT_CONNECTED

    /** Registers [onChange] for connect/disconnect; the returned lambda unregisters it. */
    fun observe(context: Context, handler: Handler, onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(URI, false, observer)
        }.isSuccess
        return { if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
}
