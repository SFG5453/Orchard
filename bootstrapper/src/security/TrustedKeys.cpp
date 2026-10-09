/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "security/Signature.hpp"

#include "util/Files.hpp"

#include <cstdlib>

namespace orchard::boot {

// Public halves only. The private keys live with the release pipeline and
// never ship. To rotate, add the new key here one bootstrapper release before
// signing with it, then retire the old id once every client has updated.
const std::vector<TrustedKey> &trustedKeys() {
  static const std::vector<TrustedKey> keys = [] {
    std::vector<TrustedKey> list = {
        {"orchard-release-1", "-----BEGIN PUBLIC KEY-----\n"
                              "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEems7pLzn6Kto06JnWQSJTTbZRCiV\n"
                              "c7mWl98p/P0RWvcw6UEiCGe/+3Z92fUfG6cBw2ATwXq1NEnHynz34kWovA==\n"
                              "-----END PUBLIC KEY-----\n"},
    };
#ifdef ORCHARD_BOOTSTRAP_TEST_HOOKS
    // Only the test build compiles this in; release builds trust nothing else.
    if (const char *path = std::getenv("ORCHARD_BOOTSTRAP_TEST_KEY"))
      list.push_back({"orchard-test", readFile(fromUtf8(path))});
#endif
    return list;
  }();
  return keys;
}

} // namespace orchard::boot
