/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "util/Version.hpp"

#include <algorithm>
#include <cctype>

namespace orchard::boot {

namespace {

bool numeric(std::string_view s) {
  return !s.empty() && std::all_of(s.begin(), s.end(), [](unsigned char c) { return std::isdigit(c); });
}

int compareField(std::string_view a, std::string_view b) {
  if (numeric(a) && numeric(b)) {
    a.remove_prefix(std::min(a.find_first_not_of('0'), a.size()));
    b.remove_prefix(std::min(b.find_first_not_of('0'), b.size()));
    if (a.size() != b.size())
      return a.size() < b.size() ? -1 : 1;
    return a.compare(b) < 0 ? -1 : (a == b ? 0 : 1);
  }
  // Numeric identifiers sort before alphanumeric ones, as in semver.
  if (numeric(a) != numeric(b))
    return numeric(a) ? -1 : 1;
  return a.compare(b) < 0 ? -1 : (a == b ? 0 : 1);
}

int compareDotted(std::string_view a, std::string_view b) {
  while (!a.empty() || !b.empty()) {
    if (a.empty() || b.empty())
      return a.empty() ? -1 : 1;
    const std::size_t ia = std::min(a.find('.'), a.size());
    const std::size_t ib = std::min(b.find('.'), b.size());
    if (const int c = compareField(a.substr(0, ia), b.substr(0, ib)); c != 0)
      return c;
    a.remove_prefix(std::min(ia + 1, a.size()));
    b.remove_prefix(std::min(ib + 1, b.size()));
  }
  return 0;
}

} // namespace

int compareVersions(std::string_view a, std::string_view b) {
  a = a.substr(0, a.find('+'));
  b = b.substr(0, b.find('+'));
  const std::size_t da = a.find('-');
  const std::size_t db = b.find('-');
  if (const int c = compareDotted(a.substr(0, da), b.substr(0, db)); c != 0)
    return c;
  const bool preA = da != std::string_view::npos;
  const bool preB = db != std::string_view::npos;
  if (preA != preB)
    return preA ? -1 : 1;
  return preA ? compareDotted(a.substr(da + 1), b.substr(db + 1)) : 0;
}

} // namespace orchard::boot
