<!--
Copyright (C) 2026 SFG545

This file is part of Orchard.

Orchard is free software: you can redistribute it and/or modify it under the
terms of the GNU Affero General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version.

Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
details.

You should have received a copy of the GNU Affero General Public License
along with Orchard. If not, see <https://www.gnu.org/licenses/>.
-->

# Native Best Mix pair scorer

Android Best Mix calls the C++ scorer in `planner/pair_scorer.cpp` through
`NativeBestMixPlanner.kt`. It ports the desktop analysis normalization,
candidate search, gates, pair ranking, transition class, confidence, quality,
and the desktop Best Mix legacy prefilter and three-finalist queue order. If the
native library cannot load or rejects an input, `BestMixSorter.kt` uses its
existing coarse Kotlin ranking. Live playback still uses the shared JavaScript
planner so rendering and choreography are unchanged.

The scorer gives the same decision **for the same analysis input**. Best Mix's
ordinary local analysis is a DSP summary of up to the first 180 seconds and
does not run Beat This. Playback's detailed analysis has separate beat-refined
head and tail windows. A native port cannot infer that missing evidence or
turn a pair beyond the desktop 4% tempo limit into a beatmatch. Improving that
input without loading the model for every queue track is a separate task.

Run `mobile/tools/check_native_pair_scorer.sh` from the repository root to
compile the host CLI and compare it with the current desktop JavaScript. The
canary workflow runs the same check before building the APK. Android's
`NativeBestMixPlannerDeviceTest` checks parity and the integrated queue path on
the x86_64 emulator without decoding audio or running a model.

On the Android emulator, the saved real V3 pair took about 100–120 ms per call
after removing a duplicate scoring pass. A 20-track synthetic queue using the
saved V3 analyses took 3.45 seconds; process PSS rose from 143,344 KiB to
153,945 KiB during the sort and later returned near baseline. These are
emulator measurements, not predictions of physical phone performance.

The vendored nlohmann JSON single header is version 3.12.0, licensed under MIT;
its full license is in `third_party/nlohmann/LICENSE.MIT`.
