/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "util/Files.hpp"

#include "platform/Platform.hpp"
#include "util/Error.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdio>
#include <memory>

namespace orchard::boot {

fs::path fromUtf8(std::string_view text) {
  return fs::path(std::u8string(text.begin(), text.end()));
}

std::string toUtf8(const fs::path &path) {
  const std::u8string text = path.u8string();
  return std::string(text.begin(), text.end());
}

std::string readFile(const fs::path &path, std::uint64_t limit) {
  std::unique_ptr<std::FILE, int (*)(std::FILE *)> file(platform::openFile(path, "rb"), std::fclose);
  if (!file)
    throw Error("cannot read " + toUtf8(path));
  std::string data;
  char buffer[1 << 16];
  for (std::size_t got; (got = std::fread(buffer, 1, sizeof buffer, file.get())) > 0;) {
    data.append(buffer, got);
    if (data.size() > limit)
      throw Error(toUtf8(path) + " is larger than expected");
  }
  if (std::ferror(file.get()))
    throw Error("cannot read " + toUtf8(path));
  return data;
}

void writeFileAtomic(const fs::path &path, std::string_view data) {
  fs::create_directories(path.parent_path());
  fs::path temp = path;
  temp += ".tmp";
  {
    std::unique_ptr<std::FILE, int (*)(std::FILE *)> file(platform::openFile(temp, "wb"), std::fclose);
    if (!file)
      throw Error("cannot write " + toUtf8(temp));
    if (std::fwrite(data.data(), 1, data.size(), file.get()) != data.size() || std::fflush(file.get()) != 0)
      throw Error("cannot write " + toUtf8(temp));
    platform::syncFile(file.get());
  }
  fs::rename(temp, path);
}

void copyFile(const fs::path &from, const fs::path &to) {
  using FilePtr = std::unique_ptr<std::FILE, int (*)(std::FILE *)>;
  FilePtr in(platform::openFile(from, "rb"), std::fclose);
  FilePtr out(platform::openFile(to, "wb"), std::fclose);
  if (!in || !out)
    throw Error("cannot copy " + toUtf8(from) + " to " + toUtf8(to));
  char buffer[1 << 16];
  for (std::size_t got; (got = std::fread(buffer, 1, sizeof buffer, in.get())) > 0;) {
    if (std::fwrite(buffer, 1, got, out.get()) != got)
      throw Error("cannot write " + toUtf8(to));
  }
  if (std::ferror(in.get()) || std::fflush(out.get()) != 0)
    throw Error("cannot copy " + toUtf8(from));
}

bool isSafePathComponent(std::string_view part) {
  if (part.empty() || part.size() > 255 || part == "." || part == "..")
    return false;
  for (unsigned char c : part) {
    if (c < 0x20 || c == 0x7f)
      return false;
    if (std::string_view("\\:*?\"<>|").find(static_cast<char>(c)) != std::string_view::npos)
      return false;
  }
  // Windows drops trailing dots and spaces, so "a." would alias "a".
  if (part.back() == '.' || part.back() == ' ')
    return false;
  std::string stem(part.substr(0, part.find('.')));
  std::transform(stem.begin(), stem.end(), stem.begin(), [](unsigned char c) { return std::toupper(c); });
  static constexpr std::array<std::string_view, 4> devices = {"CON", "PRN", "AUX", "NUL"};
  if (std::find(devices.begin(), devices.end(), stem) != devices.end())
    return false;
  if (stem.size() == 4 && (stem.starts_with("COM") || stem.starts_with("LPT")) && stem[3] >= '0' &&
      stem[3] <= '9')
    return false;
  return true;
}

fs::path safeRelativePath(std::string_view text) {
  if (text.empty() || text.size() > 1024 || text.front() == '/')
    throw Error("unsafe manifest path \"" + std::string(text) + "\"");
  fs::path out;
  std::size_t start = 0;
  while (true) {
    const std::size_t end = std::min(text.find('/', start), text.size());
    const std::string_view part = text.substr(start, end - start);
    if (!isSafePathComponent(part))
      throw Error("unsafe manifest path \"" + std::string(text) + "\"");
    out /= fromUtf8(part);
    if (end == text.size())
      break;
    start = end + 1;
  }
  return out;
}

bool isSafeName(std::string_view name) {
  if (name.empty() || name.size() > 64 || name.front() == '.' || !isSafePathComponent(name))
    return false;
  return std::all_of(name.begin(), name.end(), [](unsigned char c) {
    return std::isalnum(c) || c == '.' || c == '_' || c == '-' || c == '+';
  });
}

void ensureInside(const fs::path &root, const fs::path &path) {
  fs::path base = fs::weakly_canonical(root);
  if (!base.has_filename())
    base = base.parent_path();
  const fs::path target = fs::weakly_canonical(path);
  auto b = base.begin();
  auto t = target.begin();
  for (; b != base.end(); ++b, ++t) {
    if (t == target.end() || *b != *t)
      throw Error("refusing to write outside the install directory: " + toUtf8(path));
  }
}

bool removeTree(const fs::path &path) noexcept {
  std::error_code error;
  fs::remove_all(path, error);
  return !error;
}

} // namespace orchard::boot
