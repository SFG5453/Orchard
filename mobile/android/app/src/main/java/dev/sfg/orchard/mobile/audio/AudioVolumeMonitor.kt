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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Snapshot and controller for the device's system media volume (`AudioManager.STREAM_MUSIC`).
 */
@Stable
class SystemVolumeController(
    val volume: Int,
    val minVolume: Int,
    val maxVolume: Int,
    val setVolume: (Int) -> Unit,
) {
    val isMuted: Boolean
        get() = volume <= minVolume

    val fraction: Float
        get() {
            val range = maxVolume - minVolume
            if (range <= 0) return 0f
            return ((volume - minVolume).toFloat() / range.toFloat()).coerceIn(0f, 1f)
        }

    fun mute() {
        setVolume(minVolume)
    }

    fun max() {
        setVolume(maxVolume)
    }

    fun stepUp(step: Int = 1) {
        setVolume((volume + step).coerceAtMost(maxVolume))
    }

    fun stepDown(step: Int = 1) {
        setVolume((volume - step).coerceAtLeast(minVolume))
    }
}

/**
 * Observes the active system media volume in real time across hardware volume buttons,
 * Bluetooth adjustments, and system settings, while providing direct controls to adjust it.
 */
@Composable
fun rememberSystemVolume(): SystemVolumeController {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val lifecycleOwner = LocalLifecycleOwner.current

    val minVolume = remember(audioManager) {
        audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    var currentVolume by remember(audioManager) {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    DisposableEffect(context, lifecycleOwner, audioManager) {
        fun refresh() {
            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }

        // BroadcastReceiver to catch physical hardware volume buttons and external route events
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refresh()
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (_: Exception) {
            try {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            } catch (_: Exception) {}
        }

        // ContentObserver on system settings
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                refresh()
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer,
            )
        } catch (_: Exception) {}

        // Refresh when returning to the foreground
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (_: Exception) {}
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    val setVolume: (Int) -> Unit = remember(audioManager, minVolume, maxVolume) {
        { targetVolume ->
            val clamped = targetVolume.coerceIn(minVolume, maxVolume)
            currentVolume = clamped
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        }
    }

    return SystemVolumeController(
        volume = currentVolume,
        minVolume = minVolume,
        maxVolume = maxVolume,
        setVolume = setVolume,
    )
}
