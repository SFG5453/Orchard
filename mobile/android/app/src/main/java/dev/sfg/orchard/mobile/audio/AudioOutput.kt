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

/** Physical class of the route audio is currently leaving the phone through. */
enum class AudioOutputType {
    PHONE_SPEAKER,
    EARPIECE,
    WIRED_HEADPHONES,
    BLUETOOTH_HEADPHONES,
    BLUETOOTH_EARBUDS,
    BLUETOOTH_SPEAKER,
    CAR,
    USB,
    HDMI,
    HEARING_AID,
    UNKNOWN,
    ;

    /** Routes whose real name is only readable with `BLUETOOTH_CONNECT`. */
    val isBluetooth: Boolean
        get() = this == BLUETOOTH_HEADPHONES ||
            this == BLUETOOTH_EARBUDS ||
            this == BLUETOOTH_SPEAKER ||
            this == CAR
}

/**
 * The active output route.
 *
 * [name] is the device's own name when we can read it (a bonded Bluetooth device with
 * `BLUETOOTH_CONNECT` granted), otherwise a generic label for the route type.
 */
data class AudioOutput(
    val type: AudioOutputType = AudioOutputType.PHONE_SPEAKER,
    val name: String = "This Device",
) {
    /** True when audio is playing out of the phone itself rather than an attached device. */
    val isPhoneItself: Boolean
        get() = type == AudioOutputType.PHONE_SPEAKER || type == AudioOutputType.EARPIECE
}

/**
 * Whether this is a tablet, by the same 600dp shortest-width rule the resource
 * system uses for `sw600dp`. Orientation cannot change the answer, so a phone
 * held sideways is still a phone.
 */
fun Context.isTabletForm(): Boolean = resources.configuration.smallestScreenWidthDp >= 600

/** What this device should call itself in output pickers: "This Tablet" or "This Phone". */
fun Context.selfDeviceLabel(): String = if (isTabletForm()) "This Tablet" else "This Phone"

/** The same distinction in lowercase, for use inside a sentence. */
fun Context.selfDeviceWord(): String = if (isTabletForm()) "tablet" else "phone"
