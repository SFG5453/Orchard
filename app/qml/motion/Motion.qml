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


pragma Singleton

import QtQuick

// Shared timings so new animations agree with each other.
// Lives in its own directory so no implicit import shadows the singleton.
QtObject {
    readonly property int fast: 120
    readonly property int normal: 180
    readonly property int slow: 280
    readonly property int enter: Easing.OutCubic
    readonly property int exit: Easing.InCubic
    // Distance a page or list item rises while fading in.
    readonly property real rise: 10
    // Per-item delay for staggered entrances; capped by callers so long lists don't drag.
    readonly property int stagger: 30
    // Wait before the loading hairline appears, and one sweep of it.
    readonly property int loadDelay: 150
    readonly property int sweep: 1100
}
