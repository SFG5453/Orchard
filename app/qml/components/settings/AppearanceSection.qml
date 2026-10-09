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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import QtQuick

// Appearance settings. Layout lives in AppearanceSectionForm.ui.qml.
AppearanceSectionForm {
    layoutStyle: OrchardAppearance.layoutStyle
    showBitrate: OrchardAppearance.showBitrate
    animatedArtworkEnabled: OrchardAppearance.animatedArtworkEnabled
    animatedCollageEnabled: OrchardAppearance.animatedCollageEnabled
    artworkSource: OrchardAppearance.artworkSource
    immersiveBackground: OrchardAppearance.immersiveBackground
    speed: OrchardAppearance.speed
    intensity: OrchardAppearance.intensity
    saturation: OrchardAppearance.saturation
    brightness: OrchardAppearance.brightness
    speedText: OrchardAppearance.speed.toFixed(2) + "×"

    layoutPicker.onPicked: value => OrchardAppearance.layoutStyle = value
    bitrateSwitch.onToggled: OrchardAppearance.showBitrate = bitrateSwitch.checked
    animatedArtworkSwitch.onToggled: OrchardAppearance.animatedArtworkEnabled = animatedArtworkSwitch.checked
    collageSwitch.onToggled: OrchardAppearance.animatedCollageEnabled = collageSwitch.checked
    sourcePicker.onPicked: value => OrchardAppearance.artworkSource = value
    resetOrderButton.onClicked: OrchardAppearance.resetMirrorOrder()
    immersiveSwitch.onToggled: OrchardAppearance.immersiveBackground = immersiveSwitch.checked

    speedSlider.onMoved: function(value) { OrchardAppearance.speed = value; }
    intensitySlider.onMoved: function(value) { OrchardAppearance.intensity = value; }
    saturationSlider.onMoved: function(value) { OrchardAppearance.saturation = value; }
    brightnessSlider.onMoved: function(value) { OrchardAppearance.brightness = value; }
    resetControlsButton.onClicked: OrchardAppearance.reset()
}
