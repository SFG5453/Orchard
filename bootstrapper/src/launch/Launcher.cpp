/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "launch/Launcher.hpp"

#include "update/Manifest.hpp"
#include "util/Error.hpp"
#include "util/Log.hpp"

namespace orchard::boot {

namespace fs = std::filesystem;

namespace {

struct Expander {
  const Layout &layout;
  const std::string &version;
  const std::map<std::string, std::string> &components;

  std::string operator()(const std::string &value) const {
    if (value.empty() || value.front() != '{')
      return value;
    const std::size_t close = value.find('}');
    const std::string key = value.substr(1, close - 1);
    fs::path base;
    if (key == "app") {
      base = layout.versionDir(version);
    } else if (key == "root") {
      base = layout.root;
    } else {
      const auto it = components.find(key.substr(10));
      if (it == components.end())
        throw Error("launch spec names component " + key.substr(10) + ", which this version does not use");
      base = layout.componentDir(it->first, it->second);
    }
    if (close + 1 < value.size())
      base /= safeRelativePath(value.substr(close + 2));
    return toUtf8(base.make_preferred());
  }
};

} // namespace

platform::Process launchVersion(const Layout &layout, const InstallState &state, const std::string &version,
                                const std::vector<std::string> &args) {
  const auto info = state.version(version);
  if (!info)
    throw Error("version " + version + " is not installed");
  // Revalidated: install.json is local and could have been edited.
  const LaunchSpec launch = parseLaunch(info->launch);
  const Expander expand{layout, version, info->components};

  platform::Environment env = platform::currentEnvironment();
  for (const auto &[name, entries] : launch.prependPaths) {
    const std::string key = platform::environmentKey(env, name);
    std::string joined;
    for (const std::string &entry : entries)
      joined += (joined.empty() ? "" : std::string(1, platform::pathListSeparator())) + expand(entry);
    const auto existing = env.find(key);
    if (existing != env.end() && !existing->second.empty())
      joined += platform::pathListSeparator() + existing->second;
    env[key] = joined;
  }
  for (const auto &[name, value] : launch.env)
    env[platform::environmentKey(env, name)] = expand(value);
  env[platform::environmentKey(env, "ORCHARD_BOOTSTRAPPER")] = toUtf8(layout.bootstrapper());
  env[platform::environmentKey(env, "ORCHARD_INSTALL_ROOT")] = toUtf8(layout.root);
  env[platform::environmentKey(env, "ORCHARD_INSTALLED_VERSION")] = version;
  env[platform::environmentKey(env, "ORCHARD_UPDATE_CHANNEL")] = state.channel();

  const fs::path program = layout.versionDir(version) / safeRelativePath(launch.executable);
  std::error_code error;
  if (!fs::is_regular_file(program, error))
    throw Error("Orchard " + version + " is missing " + launch.executable);
  platform::SpawnOptions options;
  for (const std::string &arg : launch.args)
    options.args.push_back(expand(arg));
  options.args.insert(options.args.end(), args.begin(), args.end());
  options.env = std::move(env);
  log::info("launching " + version);
  return platform::spawn(program, options);
}

} // namespace orchard::boot
