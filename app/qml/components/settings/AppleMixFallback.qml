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

// Apple Music fallback row. Layout lives in AppleMixFallbackForm.ui.qml.
AppleMixFallbackForm {
    visible: OrchardPlayback.adaptiveMix.mode === "adaptive" && OrchardHome.homeSource === "apple_music"
             && OrchardPlayback.adaptiveMix.appleMatchesYouTube
    enabled: OrchardPlayback.crossfadeEnabled
    scanAvailable: OrchardPlayback.adaptiveMix.appleScanAvailable
    fallback: OrchardPlayback.adaptiveMix.appleFallback

    picker.onPicked: value => OrchardPlayback.adaptiveMix.appleFallback = value
}
