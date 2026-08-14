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

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import dev.sfg.orchard.connect.R
import dev.sfg.orchard.connect.app.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient

internal object OrchardWidgetRenderer {
    suspend fun updateAll(
        context: Context,
        state: OrchardWidgetState,
        client: OkHttpClient,
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val playerIds = manager.getAppWidgetIds(
            ComponentName(context, OrchardPlayerWidgetProvider::class.java)
        )
        val recentIds = manager.getAppWidgetIds(
            ComponentName(context, OrchardRecentWidgetProvider::class.java)
        )
        if (playerIds.isEmpty() && recentIds.isEmpty()) return

        val loader = WidgetArtworkLoader(context, client)
        val artwork = coroutineScope {
            (listOf(state.currentTrack) + state.recentlyPlayed.take(4))
                .map { track -> async { track?.artworkUrl?.let { loader.load(it) } } }
                .awaitAll()
        }
        val currentArtwork = artwork.firstOrNull()
        val recentArtwork = artwork.drop(1)
        val accent = currentArtwork?.let(::dominantAccent)
            ?: recentArtwork.firstOrNull { it != null }?.let(::dominantAccent)
            ?: ORCHARD_MINT

        playerIds.forEach { id ->
            val minHeight = manager.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val expanded = minHeight >= EXPANDED_HEIGHT_DP
            manager.updateAppWidget(
                id,
                playerViews(context, state, currentArtwork, accent, expanded),
            )
        }
        recentIds.forEach { id ->
            manager.updateAppWidget(
                id,
                recentViews(context, state, recentArtwork, accent),
            )
        }
    }

    private fun playerViews(
        context: Context,
        state: OrchardWidgetState,
        artwork: Bitmap?,
        accent: Int,
        expanded: Boolean,
    ): RemoteViews {
        val layout = if (expanded) R.layout.widget_player_expanded else R.layout.widget_player_compact
        val views = RemoteViews(context.packageName, layout)
        val track = state.currentTrack
        val backgroundHeight = if (expanded) 176 else 88
        views.setImageViewBitmap(
            R.id.widget_background,
            widgetBackground(accent, BACKGROUND_WIDTH, backgroundHeight),
        )
        if (artwork != null) {
            views.setImageViewBitmap(
                R.id.widget_artwork,
                roundedArtwork(artwork, if (expanded) 136 else 104),
            )
        } else {
            views.setImageViewResource(R.id.widget_artwork, R.drawable.ic_widget_orchard)
        }
        views.setTextViewText(
            R.id.widget_eyebrow,
            if (track == null) context.getString(R.string.widget_ready)
            else context.getString(R.string.widget_now_playing),
        )
        views.setTextViewText(
            R.id.widget_title,
            track?.title ?: context.getString(R.string.widget_nothing_playing),
        )
        views.setTextViewText(
            R.id.widget_artist,
            track?.artist ?: context.getString(R.string.widget_open_orchard),
        )
        if (expanded) {
            views.setTextViewText(R.id.widget_album, track?.album.orEmpty())
            views.setViewVisibility(
                R.id.widget_album,
                if (track?.album.isNullOrBlank()) View.GONE else View.VISIBLE,
            )
        }
        val progress = if (state.durationMs > 0) {
            ((state.positionMs.coerceIn(0, state.durationMs) * PROGRESS_MAX) / state.durationMs).toInt()
        } else {
            0
        }
        views.setProgressBar(R.id.widget_progress, PROGRESS_MAX, progress, false)
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setContentDescription(
            R.id.widget_play_pause,
            context.getString(if (state.isPlaying) R.string.pause else R.string.play),
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setOnClickPendingIntent(
            R.id.widget_previous,
            OrchardPlayerWidgetProvider.actionIntent(
                context,
                OrchardPlayerWidgetProvider.ACTION_PREVIOUS,
                REQUEST_PREVIOUS,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            OrchardPlayerWidgetProvider.actionIntent(
                context,
                OrchardPlayerWidgetProvider.ACTION_TOGGLE,
                REQUEST_TOGGLE,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            OrchardPlayerWidgetProvider.actionIntent(
                context,
                OrchardPlayerWidgetProvider.ACTION_NEXT,
                REQUEST_NEXT,
            ),
        )
        return views
    }

    private fun recentViews(
        context: Context,
        state: OrchardWidgetState,
        artwork: List<Bitmap?>,
        accent: Int,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_recent)
        views.setImageViewBitmap(
            R.id.widget_background,
            widgetBackground(accent, BACKGROUND_WIDTH, 176),
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setViewVisibility(
            R.id.widget_recent_empty,
            if (state.recentlyPlayed.isEmpty()) View.VISIBLE else View.GONE,
        )

        val cells = intArrayOf(
            R.id.widget_recent_1,
            R.id.widget_recent_2,
            R.id.widget_recent_3,
            R.id.widget_recent_4,
        )
        val covers = intArrayOf(
            R.id.widget_recent_art_1,
            R.id.widget_recent_art_2,
            R.id.widget_recent_art_3,
            R.id.widget_recent_art_4,
        )
        val labels = intArrayOf(
            R.id.widget_recent_label_1,
            R.id.widget_recent_label_2,
            R.id.widget_recent_label_3,
            R.id.widget_recent_label_4,
        )
        cells.indices.forEach { index ->
            val track = state.recentlyPlayed.getOrNull(index)
            views.setViewVisibility(cells[index], if (track == null) View.INVISIBLE else View.VISIBLE)
            if (track != null) {
                val bitmap = artwork.getOrNull(index)
                if (bitmap != null) {
                    views.setImageViewBitmap(covers[index], roundedArtwork(bitmap, 112))
                } else {
                    views.setImageViewResource(covers[index], R.drawable.ic_widget_orchard)
                }
                views.setTextViewText(labels[index], track.title)
                views.setContentDescription(
                    covers[index],
                    context.getString(R.string.widget_play_track, track.title, track.artist),
                )
                views.setOnClickPendingIntent(
                    cells[index],
                    OrchardPlayerWidgetProvider.actionIntent(
                        context,
                        OrchardPlayerWidgetProvider.ACTION_PLAY_RECENT,
                        REQUEST_RECENT + index,
                        track.id,
                    ),
                )
            }
        }
        return views
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Orchard's signature artwork wash: album color blooms into the dark canopy on a diagonal. */
    private fun widgetBackground(accent: Int, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = height.coerceAtMost(width) * 0.12f
        val baseAccent = ColorUtils.blendARGB(accent, CANOPY_NIGHT, 0.58f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(baseAccent, CANOPY_SURFACE, CANOPY_NIGHT),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.shader = RadialGradient(
            width * 0.12f,
            height * 0.1f,
            width * 0.7f,
            ColorUtils.setAlphaComponent(accent, 118),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(bounds, radius, radius, paint)
        return output
    }

    private fun roundedArtwork(source: Bitmap, size: Int): Bitmap {
        val edge = minOf(source.width, source.height)
        val left = (source.width - edge) / 2
        val top = (source.height - edge) / 2
        val square = Bitmap.createBitmap(source, left, top, edge, edge)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(output).drawRoundRect(
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            size * 0.16f,
            size * 0.16f,
            paint,
        )
        return output
    }

    /** Selects a vivid mid-tone from the cover instead of averaging it into muddy gray. */
    private fun dominantAccent(source: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(source, 24, 24, true)
        val hsl = FloatArray(3)
        var selected = ORCHARD_MINT
        var bestScore = 0f
        for (x in 0 until sample.width) {
            for (y in 0 until sample.height) {
                val color = sample.getPixel(x, y)
                ColorUtils.colorToHSL(color, hsl)
                val saturation = hsl[1]
                val lightness = hsl[2]
                val score = saturation * (1f - kotlin.math.abs(lightness - 0.55f))
                if (lightness in 0.2f..0.82f && score > bestScore) {
                    bestScore = score
                    hsl[1] = saturation.coerceAtLeast(0.42f)
                    hsl[2] = lightness.coerceIn(0.42f, 0.64f)
                    selected = ColorUtils.HSLToColor(hsl)
                }
            }
        }
        return selected
    }

    private const val EXPANDED_HEIGHT_DP = 112
    private const val BACKGROUND_WIDTH = 336
    private const val PROGRESS_MAX = 1_000
    private const val REQUEST_OPEN = 900
    private const val REQUEST_PREVIOUS = 901
    private const val REQUEST_TOGGLE = 902
    private const val REQUEST_NEXT = 903
    private const val REQUEST_RECENT = 920
    private const val ORCHARD_MINT = 0xFF2FDF93.toInt()
    private const val CANOPY_NIGHT = 0xFF0B0E11.toInt()
    private const val CANOPY_SURFACE = 0xFF1D232D.toInt()
}
