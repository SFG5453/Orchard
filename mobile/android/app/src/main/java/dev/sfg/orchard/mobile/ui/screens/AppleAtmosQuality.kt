/* Copyright (C) 2026 SFG545. Licensed under the GNU Affero General Public License v3 or later. */
package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.model.atmosQualityAvailable
import dev.sfg.orchard.mobile.playback.apple.AppleAtmosCapability

@Composable
internal fun appleAtmosQualityAvailable(settings: OrchardSettings): Boolean {
    val connected = appleMusicConnectionStatus().connected
    val decoder = remember { AppleAtmosCapability.decoderAvailable }
    return atmosQualityAvailable(settings.homeSource, connected, decoder)
}
