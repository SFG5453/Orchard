/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "security/Signature.hpp"

#include "security/Hash.hpp"
#include "util/Json.hpp"

#include <mbedtls/base64.h>
#include <mbedtls/pk.h>

#include <memory>

namespace orchard::boot {

namespace {

std::string decodeBase64(const std::string &text, std::string_view what) {
  std::size_t size = 0;
  mbedtls_base64_decode(nullptr, 0, &size, reinterpret_cast<const unsigned char *>(text.data()), text.size());
  std::string out(size, '\0');
  if (mbedtls_base64_decode(reinterpret_cast<unsigned char *>(out.data()), out.size(), &size,
                            reinterpret_cast<const unsigned char *>(text.data()), text.size()) != 0)
    throw Error(std::string(what) + " has invalid base64");
  out.resize(size);
  return out;
}

bool verify(const TrustedKey &key, std::string_view payload, const std::string &signature) {
  mbedtls_pk_context pk;
  mbedtls_pk_init(&pk);
  std::unique_ptr<mbedtls_pk_context, void (*)(mbedtls_pk_context *)> guard(&pk, mbedtls_pk_free);
  // mbedtls wants the terminating NUL counted in PEM input.
  if (mbedtls_pk_parse_public_key(&pk, reinterpret_cast<const unsigned char *>(key.pem.c_str()),
                                  key.pem.size() + 1) != 0 ||
      !mbedtls_pk_can_do(&pk, MBEDTLS_PK_ECDSA))
    throw Error("trusted key " + key.id + " is unusable");
  unsigned char digest[32];
  Sha256 hash;
  hash.update(payload.data(), payload.size());
  hash.finish(digest);
  return mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, digest, sizeof digest,
                           reinterpret_cast<const unsigned char *>(signature.data()), signature.size()) == 0;
}

} // namespace

std::string openSignedEnvelope(std::string_view envelope, std::string_view what) {
  const Json doc = parseJson(envelope, what);
  if (stringField(doc, "format", what) != "orchard-signed-v1")
    throw Error(std::string(what) + " uses an unknown envelope format");
  if (stringField(doc, "algorithm", what) != "ecdsa-p256-sha256")
    throw Error(std::string(what) + " uses an unknown signature algorithm");
  const std::string keyId = stringField(doc, "keyId", what);
  const TrustedKey *key = nullptr;
  for (const TrustedKey &candidate : trustedKeys()) {
    if (candidate.id == keyId)
      key = &candidate;
  }
  if (!key)
    throw Error(std::string(what) + " is signed by unknown key \"" + keyId + "\"");
  std::string payload = decodeBase64(stringField(doc, "payload", what), what);
  const std::string signature = decodeBase64(stringField(doc, "signature", what), what);
  if (!verify(*key, payload, signature))
    throw Error(std::string(what) + " has an invalid signature");
  return payload;
}

} // namespace orchard::boot
