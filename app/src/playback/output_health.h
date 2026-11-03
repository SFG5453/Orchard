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

#pragma once

#include <algorithm>
#include <atomic>

// Output underruns since launch, read by bug-report diagnostics.
namespace OutputHealth {
inline std::atomic<int> underruns{0};
inline std::atomic<long long> longestGapMs{0};
inline std::atomic<int> sinkBufferMs{0};

inline void recordGap(long long ms) {
  long long longest = longestGapMs.load();
  while (ms > longest && !longestGapMs.compare_exchange_weak(longest, ms)) {
  }
}
} // namespace OutputHealth
