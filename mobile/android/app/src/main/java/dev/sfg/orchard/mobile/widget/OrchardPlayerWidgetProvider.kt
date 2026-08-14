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
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import dev.sfg.orchard.mobile.model.CatalogJson
import dev.sfg.orchard.mobile.playback.OrchardPlaybackService

class OrchardPlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        OrchardWidgetUpdater.requestRender(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        OrchardWidgetUpdater.requestRender(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in widgetActions) {
            super.onReceive(context, intent)
            return
        }
        val serviceIntent = Intent(context, OrchardPlaybackService::class.java)
            .setAction(intent.action)
        if (intent.action == ACTION_PLAY_RECENT) {
            val id = intent.getStringExtra(EXTRA_TRACK_ID).orEmpty()
            val track = OrchardWidgetStateStore(context).load().recentlyPlayed
                .firstOrNull { it.id == id }
                ?: return
            serviceIntent.putExtra(EXTRA_TRACK_JSON, CatalogJson.track(track).toString())
        }
        // Widget taps are explicit user gestures and therefore the right place to start playback's
        // foreground service. Media3 promotes itself as soon as play() begins.
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        internal const val ACTION_TOGGLE = "dev.sfg.orchard.mobile.widget.TOGGLE"
        internal const val ACTION_PREVIOUS = "dev.sfg.orchard.mobile.widget.PREVIOUS"
        internal const val ACTION_NEXT = "dev.sfg.orchard.mobile.widget.NEXT"
        internal const val ACTION_PLAY_RECENT = "dev.sfg.orchard.mobile.widget.PLAY_RECENT"
        internal const val EXTRA_TRACK_ID = "track_id"
        internal const val EXTRA_TRACK_JSON = "track_json"

        private val widgetActions = setOf(
            ACTION_TOGGLE,
            ACTION_PREVIOUS,
            ACTION_NEXT,
            ACTION_PLAY_RECENT,
        )

        internal fun actionIntent(
            context: Context,
            action: String,
            requestCode: Int,
            trackId: String? = null,
        ): PendingIntent {
            val intent = Intent(context, OrchardPlayerWidgetProvider::class.java)
                .setAction(action)
                .putExtra(EXTRA_TRACK_ID, trackId)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
