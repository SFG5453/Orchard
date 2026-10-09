/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "install/InstallState.hpp"

#include "platform/Platform.hpp"
#include "util/Log.hpp"

#include <algorithm>

namespace orchard::boot {

namespace {

std::filesystem::path backupPath(const Layout &layout) {
  std::filesystem::path path = layout.installJson();
  path += ".bak";
  return path;
}

Json &objectAt(Json &doc, const char *key) {
  if (!doc[key].is_object())
    doc[key] = Json::object();
  return doc[key];
}

} // namespace

std::optional<InstallState> InstallState::load(const Layout &layout) {
  std::error_code error;
  if (!std::filesystem::exists(layout.installJson(), error)) {
    // A crash between the backup copy and the rename can leave only the backup.
    if (!std::filesystem::exists(backupPath(layout), error))
      return std::nullopt;
  }
  for (const auto &path : {layout.installJson(), backupPath(layout)}) {
    try {
      Json doc = parseJson(readFile(path, 16u << 20), "install.json");
      if (optionalUint(doc, "schema", 1) > 1)
        log::warn("install.json was written by a newer bootstrapper; keeping its extra fields");
      return InstallState(std::move(doc));
    } catch (const Error &e) {
      log::warn(std::string("cannot use ") + toUtf8(path) + ": " + e.what());
    }
  }
  throw Error("install.json is unreadable; run with --repair");
}

InstallState InstallState::fresh(const std::string &channel, const std::string &platform) {
  Json doc = Json::object();
  doc["schema"] = 1;
  doc["channel"] = channel;
  doc["platform"] = platform;
  doc["autoUpdate"] = true;
  return InstallState(std::move(doc));
}

InstallState InstallState::modify(const Layout &layout, const std::function<void(InstallState &)> &change) {
  std::filesystem::create_directories(layout.root);
  const auto lock = platform::FileLock::acquire(layout.stateLock(), std::chrono::seconds(10));
  if (!lock)
    throw Error("install state is locked by another Orchard process");
  std::optional<InstallState> state = load(layout);
  if (!state)
    throw Error("Orchard is not installed in " + toUtf8(layout.root));
  change(*state);
  state->save(layout);
  return *state;
}

void InstallState::save(const Layout &layout) const {
  std::error_code error;
  if (std::filesystem::exists(layout.installJson(), error)) {
    try {
      copyFile(layout.installJson(), backupPath(layout));
    } catch (const Error &e) {
      log::warn(e.what());
    }
  }
  writeFileAtomic(layout.installJson(), doc_.dump(2) + "\n");
}

void InstallState::set(const char *key, const std::string &value) {
  if (value.empty())
    doc_.erase(key);
  else
    doc_[key] = value;
}

std::optional<InstallState::VersionInfo> InstallState::version(const std::string &version) const {
  const auto versions = doc_.find("versions");
  if (versions == doc_.end() || !versions->is_object() || !versions->contains(version))
    return std::nullopt;
  const Json &entry = (*versions)[version];
  VersionInfo info;
  if (const auto c = entry.find("components"); c != entry.end() && c->is_object()) {
    for (const auto &[name, value] : c->items()) {
      if (value.is_string())
        info.components[name] = value.get<std::string>();
    }
  }
  info.launch = entry.value("launch", Json::object());
  info.icon = optionalString(entry, "icon");
  info.healthy = optionalBool(entry, "healthy");
  info.launchAttempts = optionalUint(entry, "launchAttempts");
  return info;
}

void InstallState::putVersion(const std::string &version, const VersionInfo &info) {
  Json &entry = objectAt(doc_, "versions")[version];
  if (!entry.is_object())
    entry = Json::object();
  entry["components"] = info.components;
  entry["launch"] = info.launch;
  entry["icon"] = info.icon;
  entry["healthy"] = info.healthy;
  entry["launchAttempts"] = info.launchAttempts;
}

void InstallState::eraseVersion(const std::string &version) { objectAt(doc_, "versions").erase(version); }

std::vector<std::string> InstallState::versions() const {
  std::vector<std::string> out;
  if (const auto v = doc_.find("versions"); v != doc_.end() && v->is_object()) {
    for (const auto &[name, value] : v->items())
      out.push_back(name);
  }
  return out;
}

void InstallState::setHealth(const std::string &version, bool healthy, std::uint64_t attempts) {
  Json &entry = objectAt(doc_, "versions")[version];
  if (!entry.is_object())
    return;
  entry["healthy"] = healthy;
  entry["launchAttempts"] = attempts;
}

void InstallState::activate(const std::string &version) {
  const std::string outgoing = current();
  if (outgoing != version)
    set("previous", outgoing);
  set("current", version);
  if (pending() == version)
    set("pending", "");
  if (const auto info = this->version(version))
    doc_["components"] = info->components;
}

std::string InstallState::componentDigest(const std::string &name, const std::string &version) const {
  const auto all = doc_.find("installedComponents");
  if (all == doc_.end() || !all->is_object())
    return {};
  const auto byName = all->find(name);
  if (byName == all->end() || !byName->is_object())
    return {};
  return optionalString(*byName, version);
}

void InstallState::recordComponent(const std::string &name, const std::string &version, const std::string &digest) {
  Json &byName = objectAt(doc_, "installedComponents")[name];
  if (!byName.is_object())
    byName = Json::object();
  byName[version] = digest;
}

void InstallState::forgetComponent(const std::string &name, const std::string &version) {
  Json &all = objectAt(doc_, "installedComponents");
  if (all.contains(name) && all[name].is_object()) {
    all[name].erase(version);
    if (all[name].empty())
      all.erase(name);
  }
}

std::map<std::string, std::vector<std::string>> InstallState::installedComponents() const {
  std::map<std::string, std::vector<std::string>> out;
  if (const auto all = doc_.find("installedComponents"); all != doc_.end() && all->is_object()) {
    for (const auto &[name, versions] : all->items()) {
      if (versions.is_object()) {
        for (const auto &[version, digest] : versions.items())
          out[name].push_back(version);
      }
    }
  }
  return out;
}

bool InstallState::skipped(const std::string &version) const {
  const auto list = doc_.find("skippedVersions");
  return list != doc_.end() && list->is_array() && std::find(list->begin(), list->end(), version) != list->end();
}

void InstallState::skip(const std::string &version) {
  if (!skipped(version)) {
    if (!doc_["skippedVersions"].is_array())
      doc_["skippedVersions"] = Json::array();
    doc_["skippedVersions"].push_back(version);
  }
}

std::uint64_t InstallState::sequence(const std::string &channel) const {
  const auto all = doc_.find("channelSequence");
  return all != doc_.end() && all->is_object() ? optionalUint(*all, channel) : 0;
}

void InstallState::setSequence(const std::string &channel, std::uint64_t sequence) {
  objectAt(doc_, "channelSequence")[channel] = sequence;
}

std::vector<std::string> InstallState::created() const {
  std::vector<std::string> out;
  if (const auto list = doc_.find("created"); list != doc_.end() && list->is_array()) {
    for (const Json &entry : *list) {
      if (entry.is_string())
        out.push_back(entry.get<std::string>());
    }
  }
  return out;
}

void InstallState::addCreated(const std::vector<std::string> &entries) {
  if (!doc_["created"].is_array())
    doc_["created"] = Json::array();
  for (const std::string &entry : entries) {
    if (std::find(doc_["created"].begin(), doc_["created"].end(), entry) == doc_["created"].end())
      doc_["created"].push_back(entry);
  }
}

} // namespace orchard::boot
