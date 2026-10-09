/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "app/Bootstrapper.hpp"

#include "install/Activation.hpp"
#include "install/Installer.hpp"
#include "install/Repair.hpp"
#include "launch/Launcher.hpp"
#include "platform/Platform.hpp"
#include "update/ReleaseClient.hpp"
#include "update/SelfUpdate.hpp"
#include "update/UpdateManager.hpp"
#include "util/Log.hpp"

#include <chrono>
#include <cstdio>

namespace orchard::boot {

namespace fs = std::filesystem;

namespace {

constexpr std::uint64_t kCheckInterval = 6 * 60 * 60;
// A new version that survives this long without crashing counts as healthy.
constexpr auto kHealthWindow = std::chrono::seconds(30);
constexpr std::uint64_t kFailuresBeforeRollback = 2;

std::uint64_t unixNow() {
  return static_cast<std::uint64_t>(
      std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count());
}

void printJson(const Json &value) {
  std::printf("%s\n", value.dump(2).c_str());
  std::fflush(stdout);
}

fs::path resolveRoot(const Options &options) {
  if (options.root)
    return fs::absolute(fromUtf8(*options.root));
  // A bootstrapper sitting in an install root manages that root.
  const fs::path here = platform::selfExecutable().parent_path();
  std::error_code error;
  if (fs::exists(here / "install.json", error))
    return here;
  return platform::defaultInstallRoot();
}

} // namespace

Bootstrapper::Bootstrapper(std::vector<std::string> args) : args_(std::move(args)) {}

int Bootstrapper::run() {
  try {
    options_ = parseOptions(args_);
  } catch (const std::exception &e) {
    std::fprintf(stderr, "orchard: %s\n\n%s", e.what(), usage());
    return 2;
  }
  log::setEcho(options_.verbose);
  if (options_.command == Command::Help) {
    std::fputs(usage(), stdout);
    return 0;
  }
  if (options_.command == Command::Version) {
    std::puts(ORCHARD_BOOTSTRAPPER_VERSION);
    return 0;
  }
  platform_ = platform::platformId();
  layout_.root = resolveRoot(options_);
  std::error_code error;
  if (fs::exists(layout_.root, error))
    log::open(layout_.logFile());

  try {
    return dispatch();
  } catch (const Relaunch &) {
    log::info("relaunching the updated bootstrapper");
    return delegate();
  } catch (const Cancelled &) {
    log::info("cancelled");
    return 3;
  } catch (const std::exception &e) {
    log::error(e.what());
    const bool tooOld = dynamic_cast<const TooOld *>(&e) != nullptr;
    if (!platform::stderrIsTerminal() && (options_.command == Command::Launch || options_.command == Command::Install ||
                                          options_.command == Command::Repair)) {
      if (auto ui = platform::createGraphicalUi())
        ui->showError(e.what());
    }
    return tooOld ? 4 : 1;
  }
}

int Bootstrapper::dispatch() {
  if (platform_.empty())
    throw Error("this platform is not supported by the Orchard installer");
  if (options_.channel && options_.command != Command::SetChannel && InstallState::load(layout_))
    setChannel(false);
  switch (options_.command) {
  case Command::Launch: return launch();
  case Command::Install: return install();
  case Command::CheckUpdate: return checkUpdate();
  case Command::Update: return update();
  case Command::UpdateOnExit: return updateOnExit();
  case Command::Status: return status();
  case Command::Repair: return repairInstall();
  case Command::Rollback: return rollbackInstall();
  case Command::SetChannel: return setChannel(true);
  case Command::SetAutoUpdate: return setAutoUpdate();
  case Command::ConfirmHealthy: return confirmHealthy();
  case Command::Cancel: return cancelDownload();
  case Command::Uninstall: return uninstallInstall();
  case Command::BackgroundUpdate: return backgroundUpdate();
  case Command::Help:
  case Command::Version: break;
  }
  return 0;
}

bool Bootstrapper::interactive() const { return platform::stderrIsTerminal(); }

bool Bootstrapper::runsInstalledCopy() const {
  std::error_code error;
  return !fs::exists(layout_.bootstrapper(), error) || fs::equivalent(platform::selfExecutable(), layout_.bootstrapper(), error);
}

int Bootstrapper::delegate() const {
  platform::SpawnOptions spawn;
  spawn.args = args_;
  if (!options_.root) {
    spawn.args.insert(spawn.args.begin(), toUtf8(layout_.root));
    spawn.args.insert(spawn.args.begin(), "--root");
  }
  platform::Process process = platform::spawn(layout_.bootstrapper(), spawn);
  const std::optional<int> code = platform::waitProcess(process, std::chrono::hours(24));
  platform::releaseProcess(process);
  return code.value_or(0);
}

int Bootstrapper::launch() {
  std::optional<InstallState> state = InstallState::load(layout_);
  if (!state || state->current().empty())
    return install();
  if (!runsInstalledCopy())
    return delegate();
  removeOldBootstrapper(layout_);
  activatePending(layout_);
  return startAndWatch(true);
}

int Bootstrapper::install() {
  std::optional<InstallState> state = InstallState::load(layout_);
  if (!state || state->current().empty()) {
    auto ui = interactive() ? createConsoleUi() : platform::createGraphicalUi();
    if (!ui)
      ui = createNullUi(true);
    CancelToken cancel;
    try {
      installFresh(layout_, options_.server, platform_, options_.channel.value_or("stable"), *ui, cancel);
    } catch (...) {
      ui->close();
      throw;
    }
    ui->close();
  } else if (!runsInstalledCopy()) {
    return delegate();
  }
  return options_.noLaunch ? 0 : startAndWatch(false);
}

int Bootstrapper::startAndWatch(bool allowRollback) {
  std::optional<InstallState> state = InstallState::load(layout_);
  std::string version = state->current();
  auto info = state->version(version);
  if (allowRollback && info && !info->healthy && info->launchAttempts >= kFailuresBeforeRollback &&
      !state->previous().empty()) {
    rollback(layout_, version + " failed to start " + std::to_string(info->launchAttempts) + " times");
    return startAndWatch(false);
  }
  std::error_code error;
  if (!info || !fs::is_directory(layout_.versionDir(version), error)) {
    log::warn("current version " + version + " is incomplete; repairing");
    repairInstall();
    state = InstallState::load(layout_);
    info = state->version(version);
  }
  if (!info->healthy)
    InstallState::modify(layout_, [&](InstallState &s) { s.setHealth(version, false, info->launchAttempts + 1); });

  platform::Process process = launchVersion(layout_, *state, version, options_.appArgs);
  scheduleBackgroundUpdate(*state);
  if (info->healthy) {
    platform::releaseProcess(process);
    return 0;
  }
  // First runs of a new version are watched; a quick crash counts against it.
  const std::optional<int> code = platform::waitProcess(process, kHealthWindow);
  platform::releaseProcess(process);
  if (!code || *code == 0) {
    InstallState::modify(layout_, [&](InstallState &s) { s.setHealth(version, true, 0); });
    log::info(version + " started cleanly");
    return 0;
  }
  log::warn(version + " exited with code " + std::to_string(*code) + " during its first run");
  state = InstallState::load(layout_);
  info = state->version(version);
  if (allowRollback && info && info->launchAttempts >= kFailuresBeforeRollback && !state->previous().empty()) {
    rollback(layout_, version + " keeps crashing at startup");
    return startAndWatch(false);
  }
  return *code;
}

void Bootstrapper::scheduleBackgroundUpdate(const InstallState &state) const {
  if (!state.flag("autoUpdate", true) || unixNow() - state.number("lastCheck") < kCheckInterval)
    return;
  platform::SpawnOptions spawn;
  spawn.args = {"--background-update", "--root", toUtf8(layout_.root), "--server", options_.server};
  spawn.background = true;
  std::error_code error;
  const fs::path program =
      fs::exists(layout_.bootstrapper(), error) ? layout_.bootstrapper() : platform::selfExecutable();
  try {
    platform::Process process = platform::spawn(program, spawn);
    platform::releaseProcess(process);
  } catch (const std::exception &e) {
    log::warn(std::string("cannot start the background updater: ") + e.what());
  }
}

int Bootstrapper::backgroundUpdate() {
  UpdateReporter reporter(layout_, nullptr);
  CancelToken cancel;
  try {
    UpdateManager(layout_, options_.server, platform_).stage(reporter, cancel, StageMode::Pending);
  } catch (const Relaunch &) {
    // The next launch picks up the new bootstrapper; nothing to rerun here.
  } catch (const Cancelled &) {
    reporter.fail("cancelled", "");
    return 3;
  } catch (const std::exception &e) {
    log::warn(std::string("background update failed: ") + e.what());
    reporter.fail("error", e.what());
    try {
      InstallState::modify(layout_, [](InstallState &s) { s.setNumber("lastCheck", unixNow()); });
    } catch (const std::exception &) {
    }
    return 1;
  }
  // Trim old versions once the current one has proven itself.
  const std::optional<InstallState> state = InstallState::load(layout_);
  const auto info = state ? state->version(state->current()) : std::nullopt;
  if (info && info->healthy) {
    if (const auto work = platform::FileLock::tryAcquire(layout_.workLock()))
      cleanup(layout_, platform_);
  }
  return 0;
}

int Bootstrapper::checkUpdate() {
  UpdateReporter reporter(layout_, nullptr);
  CancelToken cancel;
  const UpdateCheck check = UpdateManager(layout_, options_.server, platform_).check(reporter, cancel);
  printJson({{"channel", check.channel},
             {"current", check.current},
             {"latest", check.latest},
             {"pending", check.pending},
             {"available", check.available},
             {"downloadBytes", check.downloadBytes}});
  return 0;
}

int Bootstrapper::update() {
  auto ui = interactive() ? createConsoleUi() : createNullUi(true);
  UpdateReporter reporter(layout_, ui.get());
  CancelToken cancel;
  try {
    const std::string staged = UpdateManager(layout_, options_.server, platform_).stage(reporter, cancel, StageMode::Pending);
    ui->close();
    printJson({{"staged", staged}, {"pending", InstallState::load(layout_)->pending()}});
  } catch (const Cancelled &) {
    reporter.fail("cancelled", "");
    throw;
  } catch (const Relaunch &) {
    throw;
  } catch (const std::exception &e) {
    reporter.fail("error", e.what());
    throw;
  }
  return 0;
}

int Bootstrapper::updateOnExit() {
  if (options_.waitPid > 0 && !platform::waitForPid(options_.waitPid, std::chrono::minutes(10)))
    throw Error("Orchard did not exit; the update stays staged for the next launch");
  if (options_.noLaunch) {
    activatePending(layout_);
    return 0;
  }
  return launch();
}

int Bootstrapper::status() {
  const std::optional<InstallState> state = InstallState::load(layout_);
  Json out = {{"installed", state && !state->current().empty()},
              {"root", toUtf8(layout_.root)},
              {"platform", platform_},
              {"bootstrapperVersion", ORCHARD_BOOTSTRAPPER_VERSION},
              {"update", readUpdateStatus(layout_)}};
  if (state) {
    for (const char *key : {"channel", "current", "previous", "pending"})
      out[key] = state->get(key);
    out["installedBootstrapperVersion"] = state->get("bootstrapperVersion");
    out["autoUpdate"] = state->flag("autoUpdate", true);
    out["lastCheck"] = state->number("lastCheck");
    out["versions"] = state->versions();
  }
  printJson(out);
  return 0;
}

int Bootstrapper::repairInstall() {
  auto ui = interactive() ? createConsoleUi() : platform::createGraphicalUi();
  if (!ui)
    ui = createNullUi(true);
  ui->setHeadline("Repairing Orchard");
  UpdateReporter reporter(layout_, ui.get());
  CancelToken cancel;
  const RepairResult result = repair(layout_, options_.server, platform_, reporter, cancel);
  ui->close();
  log::info("repair finished: " + std::to_string(result.damaged) + " files rebuilt");
  if (options_.command == Command::Repair)
    printJson({{"checked", result.checked}, {"repaired", result.damaged}, {"downloadBytes", result.downloaded}});
  return 0;
}

int Bootstrapper::rollbackInstall() {
  printJson({{"current", rollback(layout_, "requested")}});
  return 0;
}

int Bootstrapper::setChannel(bool print) {
  const std::string channel = *options_.channel;
  if (!isSafeName(channel))
    throw Error("invalid channel name \"" + channel + "\"");
  const InstallState state = InstallState::modify(layout_, [&](InstallState &s) {
    if (s.channel() != channel) {
      s.set("channel", channel);
      s.setFlag("channelChanged", true);
      // Check the new channel on the next launch instead of in six hours.
      s.setNumber("lastCheck", 0);
    }
  });
  if (print)
    printJson({{"channel", state.channel()}});
  return 0;
}

int Bootstrapper::setAutoUpdate() {
  InstallState::modify(layout_, [&](InstallState &s) { s.setFlag("autoUpdate", options_.autoUpdate); });
  printJson({{"autoUpdate", options_.autoUpdate}});
  return 0;
}

int Bootstrapper::confirmHealthy() {
  InstallState::modify(layout_, [](InstallState &s) {
    if (!s.current().empty())
      s.setHealth(s.current(), true, 0);
  });
  return 0;
}

int Bootstrapper::cancelDownload() {
  writeFileAtomic(layout_.cancelFlag(), "cancel\n");
  return 0;
}

int Bootstrapper::uninstallInstall() {
  auto ui = interactive() ? createConsoleUi() : platform::createGraphicalUi();
  if (!ui)
    ui = createNullUi(options_.assumeYes);
  if (options_.assumeYes)
    ui = createNullUi(true);
  uninstall(layout_, *ui);
  std::fputs("Orchard was removed. Your library and settings were kept.\n", stderr);
  return 0;
}

} // namespace orchard::boot
