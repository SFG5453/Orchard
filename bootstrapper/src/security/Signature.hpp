/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace orchard::boot {

struct TrustedKey {
  std::string id;
  std::string pem; // SubjectPublicKeyInfo, ECDSA P-256
};

// Compiled-in release keys. Test builds may add one from ORCHARD_BOOTSTRAP_TEST_KEY.
const std::vector<TrustedKey> &trustedKeys();

// Verifies a signed envelope and returns its payload bytes:
//   {"format":"orchard-signed-v1","keyId":"...","algorithm":"ecdsa-p256-sha256",
//    "payload":"<base64>","signature":"<base64 DER>"}
// Throws on unknown keys, bad signatures or malformed envelopes.
std::string openSignedEnvelope(std::string_view envelope, std::string_view what);

} // namespace orchard::boot
