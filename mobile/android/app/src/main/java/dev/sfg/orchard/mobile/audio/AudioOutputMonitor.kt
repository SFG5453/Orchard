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

import android.Manifest
import android.app.UiModeManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Observes the active audio output route and keeps it current as devices connect and drop.
 *
 * Route type is always available. Real device names require `BLUETOOTH_CONNECT`; without it
 * Android 12+ redacts them, so we fall back to a generic label for the type.
 */
@Composable
fun rememberAudioOutput(): State<AudioOutput> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(AudioOutput()) }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(context, lifecycleOwner) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        state.value = audioManager.currentOutput(context)

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                state.value = audioManager.currentOutput(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                state.value = audioManager.currentOutput(context)
            }
        }
        val handler = Handler(Looper.getMainLooper())
        audioManager.registerAudioDeviceCallback(callback, handler)

        // Plugging into a head unit is not an audio device change on its own, so the car
        // connection is watched separately.
        val stopWatchingCar = CarConnection.observe(context, handler) {
            state.value = audioManager.currentOutput(context)
        }

        // Granting BLUETOOTH_CONNECT unredacts names but fires no device callback, and entering
        // car mode is not a device change either. Resuming covers both.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = audioManager.currentOutput(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            audioManager.unregisterAudioDeviceCallback(callback)
            stopWatchingCar()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return state
}

/** True when we could show a real device name but lack the permission to read it. */
fun Context.canReadBluetoothNames(): Boolean = hasBluetoothConnect()

/** Picks the route audio is actually leaving through, highest-priority attached device first. */
private fun AudioManager.currentOutput(context: Context): AudioOutput {
    val outputs = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val active = outputs.maxByOrNull { it.routePriority() } ?: return AudioOutput()
    val type = active.outputType(context)
    val name = active.displayName(context, type)

    // Android Auto projects over USB rather than Bluetooth, so the route alone never says "car".
    // Car mode and the car connection both do, and they outrank whatever the projection looks
    // like at the audio layer. Only the projected display enters car UI mode, so on a phone the
    // connection state is the signal that actually fires.
    val carConnection = CarConnection.state(context)
    if (carConnection != CarConnection.NOT_CONNECTED || context.isInCarMode()) {
        val label = when {
            type.isBluetooth -> name
            carConnection == CarConnection.PROJECTION -> "Android Auto"
            else -> "Car"
        }
        return AudioOutput(type = AudioOutputType.CAR, name = label)
    }
    return AudioOutput(type = type, name = name)
}

private fun Context.isInCarMode(): Boolean {
    val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return false
    return uiMode.currentModeType == Configuration.UI_MODE_TYPE_CAR
}

/**
 * Android reports every *available* output, not the selected one. Attached devices win over the
 * built-in speaker in the same order the platform routes them.
 */
private fun AudioDeviceInfo.routePriority(): Int = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 100
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 95
    AudioDeviceInfo.TYPE_HEARING_AID -> 90
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    -> 80

    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    -> 70

    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    -> 60

    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 10
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 5
    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isLeAudio()) 100 else 0
}

private fun AudioDeviceInfo.isLeAudio(): Boolean =
    type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
        type == AudioDeviceInfo.TYPE_BLE_BROADCAST

private fun AudioDeviceInfo.outputType(context: Context): AudioOutputType = when {
    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputType.PHONE_SPEAKER
    type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutputType.EARPIECE
    type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioOutputType.WIRED_HEADPHONES

    type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputType.USB

    type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC -> AudioOutputType.HDMI
    type == AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutputType.HEARING_AID
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_SPEAKER ->
        AudioOutputType.BLUETOOTH_SPEAKER

    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isLeAudio()) ->
        context.bluetoothTypeFor(rawName()) ?: AudioOutputType.BLUETOOTH_HEADPHONES

    else -> AudioOutputType.UNKNOWN
}

/**
 * Resolves a Bluetooth device's class to tell a car kit from earbuds from over-ear headphones.
 * Needs `BLUETOOTH_CONNECT`; returns null without it so the caller falls back to a generic type.
 */
private fun Context.bluetoothTypeFor(deviceName: String): AudioOutputType? {
    if (!hasBluetoothConnect()) return null
    val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: return null

    val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
    val match = bonded.firstOrNull { runCatching { it.name }.getOrNull() == deviceName }
        ?: return null

    return when (runCatching { match.bluetoothClass?.deviceClass }.getOrNull()) {
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
        -> AudioOutputType.CAR

        // Both over-ear cans and true wireless buds report WEARABLE_HEADSET, so the class alone
        // cannot separate them. Over-ear is the safer default; the name promotes to earbuds.
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        -> if (deviceName.looksLikeEarbuds()) {
            AudioOutputType.BLUETOOTH_EARBUDS
        } else {
            AudioOutputType.BLUETOOTH_HEADPHONES
        }
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
        -> AudioOutputType.BLUETOOTH_SPEAKER

        else -> AudioOutputType.BLUETOOTH_HEADPHONES
    }
}

/**
 * Naming markers shared by true wireless earbuds. Imperfect by nature, but the only signal that
 * separates buds from over-ear headphones once both claim WEARABLE_HEADSET.
 */
private val EARBUD_MARKERS = listOf(
    "bud", "pod", "tws", "earphone", "in-ear", "inear", "liberty", "gemini", "sport x",
)

private fun String.looksLikeEarbuds(): Boolean {
    val name = lowercase()
    return EARBUD_MARKERS.any { name.contains(it) }
}

/** The device's own name when readable, otherwise a generic label for the route. */
private fun AudioDeviceInfo.displayName(context: Context, type: AudioOutputType): String {
    val raw = rawName()
    val usable = raw.isNotBlank() &&
        !raw.equals(Build.MODEL, ignoreCase = true) &&
        (type.isBluetooth.not() || context.hasBluetoothConnect())

    if (usable && type.isBluetooth) return raw

    return when (type) {
        AudioOutputType.PHONE_SPEAKER, AudioOutputType.EARPIECE -> context.selfDeviceLabel()
        AudioOutputType.WIRED_HEADPHONES -> "Headphones"
        AudioOutputType.BLUETOOTH_EARBUDS -> "Earbuds"
        AudioOutputType.BLUETOOTH_HEADPHONES -> "Bluetooth"
        AudioOutputType.BLUETOOTH_SPEAKER -> "Speaker"
        AudioOutputType.CAR -> "Car"
        AudioOutputType.USB -> raw.ifBlank { "USB Audio" }
        AudioOutputType.HDMI -> "HDMI"
        AudioOutputType.HEARING_AID -> "Hearing Aid"
        AudioOutputType.UNKNOWN -> raw.ifBlank { "Output" }
    }
}

private fun AudioDeviceInfo.rawName(): String = runCatching { productName?.toString() }
    .getOrNull()
    .orEmpty()
    .trim()

private fun Context.hasBluetoothConnect(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
