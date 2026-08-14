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

package dev.sfg.orchard.mobile.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

internal class WidgetArtworkLoader(
    context: Context,
    private val client: OkHttpClient,
) {
    private val directory = context.cacheDir.resolve("widget-artwork").apply(File::mkdirs)

    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val cached = directory.resolve("${url.sha256()}.webp")
        BitmapFactory.decodeFile(cached.path)?.let { return@withContext it }

        val bytes = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.bytes().takeIf { it.size <= MAX_DOWNLOAD_BYTES }
            }
        }.getOrNull() ?: return@withContext null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        val scaled = decoded.fitInside(MAX_ARTWORK_EDGE)
        runCatching {
            cached.outputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, output)
            }
            trimCache()
        }
        scaled
    }

    private fun trimCache() {
        directory.listFiles().orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_CACHE_FILES)
            .forEach(File::delete)
    }

    private fun Bitmap.fitInside(maxEdge: Int): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= maxEdge) return this
        val scale = maxEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_ARTWORK_EDGE = 384
        const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
        const val MAX_CACHE_FILES = 24
    }
}
