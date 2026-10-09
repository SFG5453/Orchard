/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "update/Manifest.hpp"

#include "security/Hash.hpp"
#include "util/Files.hpp"

#include <algorithm>
#include <cctype>
#include <set>

namespace orchard::boot {

namespace {

constexpr std::uint64_t kMaxFileSize = 16ull << 30;
constexpr std::string_view kEmptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

void requireSchema(const Json &doc, std::string_view what) {
  const std::uint64_t schema = uintField(doc, "schema", what);
  if (schema > kSchema)
    throw TooOld(std::string(what) + " uses schema " + std::to_string(schema) +
                 "; this Orchard installer is too old to read it");
  if (schema == 0)
    throw Error(std::string(what) + " has schema 0");
}

// Objects are stored raw. A future encoding (zstd, deltas) is declared per
// object, and older bootstrappers must refuse instead of writing garbage.
void requireRawEncoding(const Json &value) {
  const std::string encoding = optionalString(value, "encoding", "identity");
  if (encoding != "identity")
    throw TooOld("object encoding \"" + encoding + "\" needs a newer Orchard installer");
}

std::string foldCase(std::string_view text) {
  std::string out(text);
  std::transform(out.begin(), out.end(), out.begin(), [](unsigned char c) { return std::tolower(c); });
  return out;
}

std::vector<FileEntry> parseFiles(const Json &value, std::string_view what) {
  if (!value.is_array())
    throw Error(std::string(what) + " files is not an array");
  std::vector<FileEntry> files;
  files.reserve(value.size());
  // Windows and default macOS volumes fold case; "A" and "a" would collide there.
  std::set<std::string> seen;
  for (const Json &entry : value) {
    files.push_back(parseFileEntry(entry));
    if (!seen.insert(foldCase(files.back().path)).second)
      throw Error(std::string(what) + " lists " + files.back().path + " twice");
  }
  return files;
}

void validateTemplate(const std::string &value, bool requirePlaceholder) {
  if (value.find('{') == std::string::npos) {
    if (requirePlaceholder)
      throw Error("launch path \"" + value + "\" must start with {app}, {root} or {component:name}");
    return;
  }
  const std::size_t close = value.find('}');
  if (value.front() != '{' || close == std::string::npos)
    throw Error("launch value \"" + value + "\" has a malformed placeholder");
  const std::string key = value.substr(1, close - 1);
  if (key != "app" && key != "root" && !(key.starts_with("component:") && isSafeName(key.substr(10))))
    throw Error("launch value \"" + value + "\" uses unknown placeholder {" + key + "}");
  const std::string rest = value.substr(close + 1);
  if (!rest.empty() && (rest.front() != '/' || rest.size() == 1))
    throw Error("launch value \"" + value + "\" has a malformed suffix");
  if (!rest.empty())
    safeRelativePath(rest.substr(1));
}

bool validEnvName(const std::string &name) {
  return !name.empty() && name.size() < 128 && std::all_of(name.begin(), name.end(), [](unsigned char c) {
    return std::isalnum(c) || c == '_';
  });
}

} // namespace

const Component *Manifest::component(std::string_view name) const {
  for (const Component &c : components) {
    if (c.name == name)
      return &c;
  }
  return nullptr;
}

FileEntry parseFileEntry(const Json &value) {
  if (!value.is_object())
    throw Error("manifest file entry is not an object");
  FileEntry entry;
  entry.path = stringField(value, "path", "manifest file entry");
  safeRelativePath(entry.path);
  const std::string what = "manifest entry " + entry.path;
  entry.size = uintField(value, "size", what);
  entry.sha256 = stringField(value, "sha256", what);
  entry.executable = optionalBool(value, "executable");
  if (!isSha256Hex(entry.sha256) || entry.size > kMaxFileSize)
    throw Error(what + " has an invalid hash or size");
  requireRawEncoding(value);
  if (const auto object = value.find("object"); object != value.end() && *object != entry.sha256)
    throw TooOld(what + " uses a transformed object; this Orchard installer is too old");
  const auto chunks = value.find("chunks");
  if (chunks == value.end()) {
    if (entry.size > 0)
      entry.chunks.push_back({entry.sha256, entry.size});
  } else {
    if (!chunks->is_array())
      throw Error(what + " chunks is not an array");
    std::uint64_t sum = 0;
    for (const Json &chunk : *chunks) {
      if (!chunk.is_object())
        throw Error(what + " has a malformed chunk");
      requireRawEncoding(chunk);
      Chunk c{stringField(chunk, "sha256", what), uintField(chunk, "size", what)};
      if (!isSha256Hex(c.sha256) || c.size == 0 || c.size > kMaxFileSize)
        throw Error(what + " has an invalid chunk");
      sum += c.size;
      entry.chunks.push_back(std::move(c));
    }
    if (sum != entry.size)
      throw Error(what + " chunk sizes do not add up to the file size");
  }
  if (entry.size == 0 && entry.sha256 != kEmptySha256)
    throw Error(what + " is empty but has a non-empty hash");
  return entry;
}

LaunchSpec parseLaunch(const Json &value) {
  if (!value.is_object())
    throw Error("manifest launch is not an object");
  LaunchSpec launch;
  launch.raw = value;
  launch.executable = stringField(value, "executable", "manifest launch");
  safeRelativePath(launch.executable);
  if (const auto args = value.find("args"); args != value.end()) {
    if (!args->is_array())
      throw Error("manifest launch args is not an array");
    for (const Json &arg : *args) {
      if (!arg.is_string())
        throw Error("manifest launch args must be strings");
      validateTemplate(arg.get<std::string>(), false);
      launch.args.push_back(arg.get<std::string>());
    }
  }
  if (const auto env = value.find("env"); env != value.end()) {
    if (!env->is_object())
      throw Error("manifest launch env is not an object");
    for (const auto &[name, entry] : env->items()) {
      if (!validEnvName(name) || !entry.is_string())
        throw Error("manifest launch env " + name + " is invalid");
      validateTemplate(entry.get<std::string>(), false);
      launch.env.emplace_back(name, entry.get<std::string>());
    }
  }
  if (const auto paths = value.find("prependPaths"); paths != value.end()) {
    if (!paths->is_object())
      throw Error("manifest launch prependPaths is not an object");
    for (const auto &[name, list] : paths->items()) {
      if (!validEnvName(name) || !list.is_array())
        throw Error("manifest launch prependPaths " + name + " is invalid");
      std::vector<std::string> entries;
      for (const Json &item : list) {
        if (!item.is_string())
          throw Error("manifest launch prependPaths " + name + " must hold strings");
        validateTemplate(item.get<std::string>(), true);
        entries.push_back(item.get<std::string>());
      }
      launch.prependPaths.emplace_back(name, std::move(entries));
    }
  }
  return launch;
}

Manifest parseManifest(std::string_view payload, std::string_view expectedVersion,
                       std::string_view expectedPlatform) {
  const Json doc = parseJson(payload, "release manifest");
  requireSchema(doc, "release manifest");
  Manifest manifest;
  manifest.version = stringField(doc, "version", "release manifest");
  manifest.platform = stringField(doc, "platform", "release manifest");
  if (!isSafeName(manifest.version) || (!expectedVersion.empty() && manifest.version != expectedVersion))
    throw Error("release manifest is for version " + manifest.version + ", expected " +
                std::string(expectedVersion));
  if (manifest.platform != expectedPlatform)
    throw Error("release manifest is for " + manifest.platform + ", expected " + std::string(expectedPlatform));
  manifest.minimumBootstrapper = optionalString(doc, "minimumBootstrapperVersion");
  manifest.files = parseFiles(field(doc, "files", "release manifest"), "release manifest");

  const Json &components = field(doc, "components", "release manifest");
  if (!components.is_object())
    throw Error("release manifest components is not an object");
  for (const auto &[name, value] : components.items()) {
    const std::string what = "component " + name;
    if (!isSafeName(name) || !value.is_object())
      throw Error(what + " has an invalid name or body");
    Component component{name, stringField(value, "version", what), parseFiles(field(value, "files", what), what), {}};
    if (!isSafeName(component.version))
      throw Error(what + " has an invalid version");
    component.digest = componentDigest(component.files);
    manifest.components.push_back(std::move(component));
  }

  manifest.launch = parseLaunch(field(doc, "launch", "release manifest"));
  const auto hasFile = [&](const std::string &path) {
    return std::any_of(manifest.files.begin(), manifest.files.end(),
                       [&](const FileEntry &f) { return f.path == path; });
  };
  if (!hasFile(manifest.launch.executable))
    throw Error("release manifest launch executable is not part of the release");
  // Every {component:x} placeholder must name a component of this release.
  const std::string launchText = manifest.launch.raw.dump();
  for (std::size_t at = launchText.find("{component:"); at != std::string::npos;
       at = launchText.find("{component:", at + 1)) {
    const std::size_t close = launchText.find('}', at);
    if (!manifest.component(launchText.substr(at + 11, close - at - 11)))
      throw Error("release manifest launch refers to a missing component");
  }
  manifest.icon = optionalString(doc, "icon");
  if (!manifest.icon.empty() && !hasFile(manifest.icon))
    throw Error("release manifest icon is not part of the release");
  return manifest;
}

ChannelRelease parseChannel(std::string_view payload, std::string_view expectedChannel) {
  const Json doc = parseJson(payload, "release channel");
  requireSchema(doc, "release channel");
  ChannelRelease release;
  release.channel = stringField(doc, "channel", "release channel");
  if (release.channel != expectedChannel)
    throw Error("release channel file is for \"" + release.channel + "\", expected \"" +
                std::string(expectedChannel) + "\"");
  release.sequence = uintField(doc, "sequence", "release channel");
  release.version = stringField(doc, "version", "release channel");
  if (!isSafeName(release.version))
    throw Error("release channel has an invalid version");
  release.minimumBootstrapper = optionalString(doc, "minimumBootstrapperVersion");

  const Json &platforms = field(doc, "platforms", "release channel");
  if (!platforms.is_object())
    throw Error("release channel platforms is not an object");
  for (const auto &[platform, value] : platforms.items()) {
    const std::string what = "release channel platform " + platform;
    if (!value.is_object())
      throw Error(what + " is not an object");
    PlatformRelease entry{stringField(value, "manifest", what), stringField(value, "sha256", what),
                          uintField(value, "size", what)};
    safeRelativePath(entry.manifest);
    if (!entry.manifest.starts_with("manifests/") || !isSha256Hex(entry.sha256))
      throw Error(what + " has an invalid manifest reference");
    release.platforms.emplace(platform, std::move(entry));
  }

  if (const auto boot = doc.find("bootstrapper"); boot != doc.end() && boot->is_object()) {
    release.bootstrapperVersion = stringField(*boot, "version", "release channel bootstrapper");
    const Json &files = field(*boot, "platforms", "release channel bootstrapper");
    if (!files.is_object())
      throw Error("release channel bootstrapper platforms is not an object");
    for (const auto &[platform, value] : files.items())
      release.bootstrappers.emplace(platform, parseFileEntry(value));
  }
  return release;
}

std::string componentDigest(const std::vector<FileEntry> &files) {
  std::vector<const FileEntry *> sorted;
  for (const FileEntry &file : files)
    sorted.push_back(&file);
  std::sort(sorted.begin(), sorted.end(), [](auto *a, auto *b) { return a->path < b->path; });
  Sha256 hash;
  for (const FileEntry *file : sorted) {
    const std::string line = file->path + '\n' + std::to_string(file->size) + '\n' + file->sha256 + '\n' +
                             (file->executable ? "x\n" : "-\n");
    hash.update(line.data(), line.size());
  }
  return hash.finishHex();
}

std::uint64_t totalSize(const std::vector<FileEntry> &files) {
  std::uint64_t sum = 0;
  for (const FileEntry &file : files)
    sum += file.size;
  return sum;
}

} // namespace orchard::boot
