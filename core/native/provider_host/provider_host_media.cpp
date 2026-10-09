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

#include "provider_host.h"

namespace orchard::provider {
namespace {

ProviderHost *hostOf(JSContext *context) {
  return static_cast<ProviderHost *>(JS_GetContextOpaque(context));
}

// Borrows a Uint8Array argument. An empty array is valid; anything else throws.
bool byteArgument(JSContext *context, JSValueConst value, std::string_view &bytes) {
  std::size_t size = 0;
  const uint8_t *data = JS_GetUint8Array(context, &size, value);
  if (!data && size == 0 && JS_GetTypedArrayType(value) != JS_TYPED_ARRAY_UINT8)
    return false;
  bytes = std::string_view(reinterpret_cast<const char *>(data), size);
  return true;
}

JSValue byteResult(JSContext *context, const std::string &bytes) {
  return JS_NewUint8ArrayCopy(context, reinterpret_cast<const uint8_t *>(bytes.data()),
                              bytes.size());
}

} // namespace

Bundle youtubeBundle() {
  return {"youtube-provider.qjc", "OrchardYouTubeProvider", "YouTube"};
}

Bundle qobuzBundle() {
  return {"qobuz-provider.qjc", "OrchardQobuzProvider", "Qobuz"};
}

void ProviderHost::defineMediaFunctions(JSValueConst global) {
  JS_SetPropertyStr(m_context, global, "__orchardHkdfSha256",
                    JS_NewCFunction(m_context, &ProviderHost::hkdfSha256, "__orchardHkdfSha256", 4));
  JS_SetPropertyStr(m_context, global, "__orchardAes128",
                    JS_NewCFunction(m_context, &ProviderHost::aes128, "__orchardAes128", 4));
}

// Audio ranges skip JSON: a byte array becomes a JSON object with a key per byte.
bool ProviderHost::settleBytes(std::uint64_t requestId, JSValueConst value) {
  std::size_t size = 0;
  const uint8_t *data = nullptr;
  if (JS_IsArrayBuffer(value)) {
    data = JS_GetArrayBuffer(m_context, &size, value);
  } else if (JS_GetTypedArrayType(value) == JS_TYPED_ARRAY_UINT8) {
    data = JS_GetUint8Array(m_context, &size, value);
  } else {
    return false;
  }
  m_platform.settledBytes(requestId, data ? std::string(reinterpret_cast<const char *>(data), size)
                                          : std::string());
  return true;
}

// __orchardHkdfSha256(key, salt, info, length) -> Uint8Array
JSValue ProviderHost::hkdfSha256(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
  std::string_view key, salt, info;
  int32_t length = 0;
  if (argc < 4 || !byteArgument(context, argv[0], key) || !byteArgument(context, argv[1], salt) ||
      !byteArgument(context, argv[2], info) || JS_ToInt32(context, &length, argv[3]) < 0 ||
      length <= 0 || length > 1024)
    return JS_ThrowTypeError(context, "hkdfSha256 needs key, salt and info bytes and a length");
  std::string out;
  if (!hostOf(context)->m_platform.hkdfSha256(key, salt, info, static_cast<std::size_t>(length), out))
    return JS_ThrowInternalError(context, "HKDF-SHA256 is unavailable on this host");
  return byteResult(context, out);
}

// __orchardAes128(mode, key, iv, data) -> Uint8Array; mode 0 is CBC decrypt, 1 is CTR.
JSValue ProviderHost::aes128(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
  int32_t mode = -1;
  std::string_view key, iv, data;
  if (argc < 4 || JS_ToInt32(context, &mode, argv[0]) < 0 || (mode != 0 && mode != 1) ||
      !byteArgument(context, argv[1], key) || !byteArgument(context, argv[2], iv) ||
      !byteArgument(context, argv[3], data) || key.size() != 16 || iv.size() != 16)
    return JS_ThrowTypeError(context, "AES-128 needs a mode, a 16-byte key and IV, and data");
  std::string out;
  const Cipher cipher = mode == 0 ? Cipher::Aes128CbcDecrypt : Cipher::Aes128Ctr;
  if (!hostOf(context)->m_platform.aes128(cipher, key, iv, data, out))
    return JS_ThrowInternalError(context, "AES-128 failed or is unavailable on this host");
  return byteResult(context, out);
}

} // namespace orchard::provider
