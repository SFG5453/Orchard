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

#include "litert_api.h"

#include <mutex>

#if defined(_WIN32)
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace {
void *openLibrary(const std::string &path, std::string *error) {
#if defined(_WIN32)
  const int wide = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
  std::wstring name(size_t(wide > 0 ? wide : 1), L'\0');
  MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, name.data(), wide);
  void *library = reinterpret_cast<void *>(LoadLibraryW(name.c_str()));
  if (!library)
    *error = "Cannot load " + path + " (error " + std::to_string(GetLastError()) + ")";
#else
  void *library = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!library) {
    const char *reason = dlerror();
    *error = reason ? reason : "Cannot load " + path;
  }
#endif
  return library;
}

void *resolve(void *library, const char *name) {
#if defined(_WIN32)
  return reinterpret_cast<void *>(GetProcAddress(static_cast<HMODULE>(library), name));
#else
  return dlsym(library, name);
#endif
}
} // namespace

const LiteRtApi *LiteRtApi::get(const std::string &path, std::string *error) {
  static std::once_flag once;
  static LiteRtApi api;
  static std::string failure;
  std::call_once(once, [&path] {
    // Never closed: XNNPACK threads outlive any one model.
    void *library = openLibrary(path, &failure);
    if (!library)
      return;
#define ORCHARD_LITERT_RESOLVE(name)                                                               \
  api.name = reinterpret_cast<decltype(api.name)>(resolve(library, #name));                        \
  if (!api.name && failure.empty())                                                                \
    failure = "LiteRT is missing " #name;
    ORCHARD_LITERT_FUNCTIONS(ORCHARD_LITERT_RESOLVE)
#undef ORCHARD_LITERT_RESOLVE
  });
  if (error)
    *error = failure;
  return failure.empty() ? &api : nullptr;
}
