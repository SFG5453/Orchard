/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "install/Activation.hpp"

#include "platform/Platform.hpp"
#include "util/Log.hpp"

#include <set>

namespace orchard::boot {

namespace fs = std::filesystem;

namespace {

bool usable(const Layout &layout, const InstallState &state, const std::string &version) {
  std::error_code error;
  return !version.empty() && state.version(version) && fs::is_directory(layout.versionDir(version), error);
}

} // namespace

std::string activatePending(const Layout &layout) {
  std::string activated;
  InstallState::modify(layout, [&](InstallState &state) {
    const std::string pending = state.pending();
    if (pending.empty())
      return;
    if (!usable(layout, state, pending) || state.skipped(pending)) {
      log::warn("dropping unusable pending version " + pending);
      state.set("pending", "");
      return;
    }
    state.activate(pending);
    activated = pending;
  });
  if (!activated.empty()) {
    log::info("activated " + activated);
    refreshIntegration(layout);
  }
  return activated;
}

std::string rollback(const Layout &layout, const std::string &reason) {
  std::string target;
  InstallState::modify(layout, [&](InstallState &state) {
    const std::string current = state.current();
    target = state.previous();
    if (!usable(layout, state, target))
      throw Error("there is no previous Orchard version to roll back to");
    state.skip(current);
    if (state.pending() == current)
      state.set("pending", "");
    state.activate(target);
    log::warn("rolled back " + current + " -> " + target + ": " + reason);
  });
  refreshIntegration(layout);
  return target;
}

void cleanup(const Layout &layout, const std::string &platform) {
  std::set<std::string> keepVersions;
  std::set<std::pair<std::string, std::string>> keepComponents;
  std::vector<std::string> dropVersions;
  std::vector<std::pair<std::string, std::string>> dropComponents;
  InstallState::modify(layout, [&](InstallState &state) {
    for (const std::string &version : {state.current(), state.previous(), state.pending()}) {
      if (const auto info = state.version(version)) {
        keepVersions.insert(version);
        for (const auto &[name, componentVersion] : info->components)
          keepComponents.emplace(name, componentVersion);
      }
    }
    for (const std::string &version : state.versions()) {
      if (!keepVersions.count(version)) {
        state.eraseVersion(version);
        dropVersions.push_back(version);
      }
    }
    for (const auto &[name, versions] : state.installedComponents()) {
      for (const std::string &version : versions) {
        if (!keepComponents.count({name, version})) {
          state.forgetComponent(name, version);
          dropComponents.emplace_back(name, version);
        }
      }
    }
  });
  for (const std::string &version : dropVersions) {
    log::info("removing old version " + version);
    std::error_code error;
    fs::remove(layout.manifestFile(version, platform), error);
  }
  // Anything on disk that install.json no longer names is debris: old
  // versions, interrupted staging, or directories Windows refused to delete
  // last time because a file was still open.
  std::error_code error;
  for (const auto &entry : fs::directory_iterator(layout.versionsDir(), error)) {
    if (!keepVersions.count(toUtf8(entry.path().filename())))
      removeTree(entry.path());
  }
  for (const auto &group : fs::directory_iterator(layout.componentsDir(), error)) {
    const std::string name = toUtf8(group.path().filename());
    for (const auto &entry : fs::directory_iterator(group.path(), error)) {
      if (!keepComponents.count({name, toUtf8(entry.path().filename())}))
        removeTree(entry.path());
    }
    if (fs::is_empty(group.path(), error))
      fs::remove(group.path(), error);
  }
  if (!dropComponents.empty())
    log::info("removed " + std::to_string(dropComponents.size()) + " unused components");
}

void refreshIntegration(const Layout &layout) {
  try {
    const std::optional<InstallState> state = InstallState::load(layout);
    if (!state || state->current().empty())
      return;
    const auto info = state->version(state->current());
    fs::path icon;
    if (info && !info->icon.empty())
      icon = layout.versionDir(state->current()) / safeRelativePath(info->icon);
    const std::vector<std::string> created =
        platform::registerInstallation(layout.root, layout.bootstrapper(), icon, state->current());
    InstallState::modify(layout, [&](InstallState &s) { s.addCreated(created); });
  } catch (const std::exception &e) {
    log::warn(std::string("desktop integration failed: ") + e.what());
  }
}

} // namespace orchard::boot
