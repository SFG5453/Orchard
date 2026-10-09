/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "util/Error.hpp"

#include <nlohmann/json.hpp>

#include <cstdint>
#include <string>
#include <string_view>

namespace orchard::boot {

using Json = nlohmann::json;

// Parse failures become Error so callers only catch one type.
inline Json parseJson(std::string_view text, std::string_view what) {
  try {
    Json value = Json::parse(text);
    if (!value.is_object())
      throw Error(std::string(what) + " is not a JSON object");
    return value;
  } catch (const Json::exception &e) {
    throw Error(std::string(what) + " is malformed: " + e.what());
  }
}

inline const Json &field(const Json &object, std::string_view key, std::string_view what) {
  const auto it = object.find(key);
  if (it == object.end())
    throw Error(std::string(what) + " is missing \"" + std::string(key) + "\"");
  return *it;
}

inline std::string stringField(const Json &object, std::string_view key, std::string_view what) {
  const Json &value = field(object, key, what);
  if (!value.is_string())
    throw Error(std::string(what) + " field \"" + std::string(key) + "\" is not a string");
  return value.get<std::string>();
}

inline std::uint64_t uintField(const Json &object, std::string_view key, std::string_view what) {
  const Json &value = field(object, key, what);
  if (!value.is_number_unsigned())
    throw Error(std::string(what) + " field \"" + std::string(key) + "\" is not an unsigned integer");
  return value.get<std::uint64_t>();
}

inline std::string optionalString(const Json &object, std::string_view key, std::string fallback = {}) {
  const auto it = object.find(key);
  return it != object.end() && it->is_string() ? it->get<std::string>() : fallback;
}

inline std::uint64_t optionalUint(const Json &object, std::string_view key, std::uint64_t fallback = 0) {
  const auto it = object.find(key);
  return it != object.end() && it->is_number_unsigned() ? it->get<std::uint64_t>() : fallback;
}

inline bool optionalBool(const Json &object, std::string_view key, bool fallback = false) {
  const auto it = object.find(key);
  return it != object.end() && it->is_boolean() ? it->get<bool>() : fallback;
}

} // namespace orchard::boot
