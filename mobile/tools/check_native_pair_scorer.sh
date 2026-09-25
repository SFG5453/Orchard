#!/usr/bin/env bash
# Copyright (C) 2026 SFG545
#
# This file is part of Orchard.
#
# Orchard is free software: you can redistribute it and/or modify it under the
# terms of the GNU Affero General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option) any
# later version.
#
# Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
# A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License
# along with Orchard. If not, see <https://www.gnu.org/licenses/>.

set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
native_cli="$(mktemp "${TMPDIR:-/tmp}/orchard-bestmix.XXXXXX")"
trap 'rm -f "$native_cli"' EXIT

if command -v clang++ >/dev/null 2>&1; then
    compiler=clang++
else
    compiler=c++
fi

"$compiler" -std=c++17 -O2 \
    -I "$project_root/mobile/android/app/src/main/cpp" \
    -I "$project_root/mobile/android/app/src/main/cpp/third_party" \
    "$project_root/mobile/android/app/src/main/cpp/planner/pair_scorer.cpp" \
    "$project_root/mobile/tools/native_pair_scorer_cli.cpp" \
    -o "$native_cli"

cd "$project_root"
node mobile/tools/check_native_pair_scorer.mjs "$native_cli"
