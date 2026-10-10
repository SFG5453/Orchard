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

// Playback settings. Layout lives in PlaybackSectionForm.ui.qml.
PlaybackSectionForm {
    id: root

    streamQuality: OrchardPlayback.streamQuality
    qobuzReachable: OrchardPlayback.qobuzReachable
    crossfadeEnabled: OrchardPlayback.crossfadeEnabled
    adaptiveMode: OrchardPlayback.adaptiveMix.mode
    crossfadeDuration: OrchardPlayback.crossfadeDuration
    gaplessEnabled: OrchardPlayback.gaplessEnabled
    exponentialVolumeEnabled: OrchardPlayback.exponentialVolumeEnabled
    autoplayEnabled: OrchardPlayback.autoplayEnabled
    slopAction: OrchardPlayback.slop.action
    nonMusicSkipMode: OrchardPlayback.nonMusicSkipMode
    queueLayout: OrchardPlayback.queueLayout

    streamQualityPicker.onPicked: value => OrchardPlayback.streamQuality = value
    slopPicker.onPicked: value => OrchardPlayback.slop.action = value
    nonMusicPicker.onPicked: value => OrchardPlayback.nonMusicSkipMode = value
    queueLayoutPicker.onPicked: value => OrchardPlayback.queueLayout = value

    crossfadeSwitch.onToggled: {
        // A remembered adaptive mode can't wake up next to a running engine.
        if (crossfadeSwitch.checked && OrchardPlayback.audioEngine.enabled && OrchardPlayback.adaptiveMix.mode === "adaptive")
            OrchardPlayback.adaptiveMix.mode = "standard";
        OrchardPlayback.crossfadeEnabled = crossfadeSwitch.checked;
    }
    crossfadeLength.onMoved: function(value) {
        OrchardPlayback.crossfadeDuration = Math.round(value);
    }
    gaplessSwitch.onToggled: OrchardPlayback.gaplessEnabled = gaplessSwitch.checked
    exponentialVolumeSwitch.onToggled: OrchardPlayback.exponentialVolumeEnabled = exponentialVolumeSwitch.checked
    autoplaySwitch.onToggled: OrchardPlayback.autoplayEnabled = autoplaySwitch.checked
}
