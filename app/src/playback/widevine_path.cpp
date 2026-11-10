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

#include "widevine_path.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QVersionNumber>

#include <algorithm>

namespace {

QString widevineArch() {
#if defined(Q_OS_WIN) && defined(Q_PROCESSOR_ARM_64)
  return QStringLiteral("win_arm64");
#elif defined(Q_OS_WIN)
  return QStringLiteral("win_x64");
#elif defined(Q_OS_MACOS) && defined(Q_PROCESSOR_ARM_64)
  return QStringLiteral("mac_arm64");
#elif defined(Q_OS_MACOS)
  return QStringLiteral("mac_x64");
#elif defined(Q_PROCESSOR_ARM_64)
  return QStringLiteral("linux_arm64");
#else
  return QStringLiteral("linux_x64");
#endif
}

#ifdef Q_OS_WIN
// Newest versioned CDM under a browser's component-updater folder.
QString newestCdm(const QString &root, const QString &relative) {
  QStringList versions = QDir(root).entryList(QDir::Dirs | QDir::NoDotAndDotDot);
  std::sort(versions.begin(), versions.end(), [](const QString &a, const QString &b) {
    return QVersionNumber::fromString(a) > QVersionNumber::fromString(b);
  });
  for (const QString &version : versions) {
    const QString path = root + QLatin1Char('/') + version + relative;
    if (QFileInfo::exists(path))
      return path;
  }
  return {};
}

// Edge's component updater installs the CDM on every Windows; Chrome also bundles one beside its binary.
QString findInstalledCdm() {
  const QString bundled = QStringLiteral("/_platform_specific/") + widevineArch() + QStringLiteral("/widevinecdm.dll");
  const QString local = qEnvironmentVariable("LOCALAPPDATA");
  for (const char *browser : {"Microsoft/Edge", "Microsoft/Edge Beta", "Microsoft/Edge Dev", "Microsoft/Edge SxS",
                              "Google/Chrome", "Google/Chrome Beta", "Chromium", "BraveSoftware/Brave-Browser"}) {
    const QString root = local + QLatin1Char('/') + QString::fromLatin1(browser) + QStringLiteral("/User Data/WidevineCdm");
    if (const QString path = newestCdm(root, bundled); !path.isEmpty())
      return path;
  }
  for (const QString &base : {qEnvironmentVariable("ProgramFiles"), qEnvironmentVariable("ProgramFiles(x86)"), local}) {
    if (base.isEmpty())
      continue;
    const QString path = newestCdm(base + QStringLiteral("/Google/Chrome/Application"), QStringLiteral("/WidevineCdm") + bundled);
    if (!path.isEmpty())
      return path;
  }
  const QDir firefox(qEnvironmentVariable("APPDATA") + QStringLiteral("/Mozilla/Firefox/Profiles"));
  for (const QString &profile : firefox.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
    const QString path = newestCdm(firefox.filePath(profile) + QStringLiteral("/gmp-widevinecdm"),
                                   QStringLiteral("/widevinecdm.dll"));
    if (!path.isEmpty())
      return path;
  }
  return {};
}
#else
struct Candidate {
  QString path;
  QVersionNumber version;
};

QString libraryName() {
#ifdef Q_OS_MACOS
  return QStringLiteral("libwidevinecdm.dylib");
#else
  return QStringLiteral("libwidevinecdm.so");
#endif
}

QString bundledRelative() {
  return QStringLiteral("/_platform_specific/") + widevineArch() + QLatin1Char('/') + libraryName();
}

// Chromium component-updater layout: <root>/<version>/_platform_specific/...
void addVersioned(QList<Candidate> &found, const QString &root, const QString &relative) {
  for (const QString &version : QDir(root).entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
    const QString path = root + QLatin1Char('/') + version + relative;
    if (QFileInfo::exists(path))
      found.append({path, QVersionNumber::fromString(version)});
  }
}

// CDM shipped beside a browser binary; its manifest.json carries the version.
void addBundled(QList<Candidate> &found, const QString &root) {
  const QString path = root + bundledRelative();
  if (!QFileInfo::exists(path))
    return;
  QFile manifest(root + QStringLiteral("/manifest.json"));
  QVersionNumber version;
  if (manifest.open(QIODevice::ReadOnly))
    version = QVersionNumber::fromString(
        QJsonDocument::fromJson(manifest.readAll()).object().value(QStringLiteral("version")).toString());
  found.append({path, version});
}

QStringList subdirs(const QString &root) {
  QStringList out;
  const QDir dir(root);
  for (const QString &name : dir.entryList(QDir::Dirs | QDir::NoDotAndDotDot))
    out.append(dir.filePath(name));
  return out;
}

// Chromium browsers, Electron and CEF apps all keep a WidevineCdm folder in their config dir.
void addConfigDirs(QList<Candidate> &found, const QString &config) {
  QStringList apps = subdirs(config);
  for (const char *vendor : {"BraveSoftware", "google", "microsoft", "opera", "Google", "Microsoft"})
    apps += subdirs(config + QLatin1Char('/') + QString::fromLatin1(vendor));
  for (const QString &app : apps)
    addVersioned(found, app + QStringLiteral("/WidevineCdm"), bundledRelative());
}

// Newest copy wins; the license server rejects revoked CDM versions.
QString newest(const QList<Candidate> &found) {
  const auto best = std::max_element(found.cbegin(), found.cend(), [](const Candidate &a, const Candidate &b) {
    return a.version < b.version;
  });
  return best == found.cend() ? QString() : best->path;
}

#ifdef Q_OS_MACOS
// Chrome bundles the CDM inside its framework and also keeps component-updater copies.
QString findInstalledCdm() {
  QList<Candidate> found;
  const QString home = QDir::homePath();
  for (const QString &apps : {QStringLiteral("/Applications"), home + QStringLiteral("/Applications")}) {
    for (const char *framework : {"Google Chrome.app/Contents/Frameworks/Google Chrome Framework.framework",
                                  "Google Chrome Beta.app/Contents/Frameworks/Google Chrome Framework.framework",
                                  "Microsoft Edge.app/Contents/Frameworks/Microsoft Edge Framework.framework",
                                  "Brave Browser.app/Contents/Frameworks/Brave Browser Framework.framework"})
      addBundled(found, apps + QLatin1Char('/') + QString::fromUtf8(framework) +
                            QStringLiteral("/Versions/Current/Libraries/WidevineCdm"));
  }
  const QString support = home + QStringLiteral("/Library/Application Support");
  addConfigDirs(found, support);
  // Firefox and its forks keep the CDM per profile: <profile>/gmp-widevinecdm/<version>/.
  for (const char *gecko : {"Firefox", "librewolf", "zen", "Waterfox", "Floorp"}) {
    for (const QString &profile : subdirs(support + QLatin1Char('/') + QString::fromLatin1(gecko) + QStringLiteral("/Profiles")))
      addVersioned(found, profile + QStringLiteral("/gmp-widevinecdm"), QLatin1Char('/') + libraryName());
  }
  return newest(found);
}
#else
// Firefox and its forks keep the CDM per profile: <profile>/gmp-widevinecdm/<version>/.
void addGeckoRoots(QList<Candidate> &found, const QString &base) {
  for (const char *root : {"/.mozilla/firefox", "/.config/mozilla/firefox", "/.librewolf", "/.config/librewolf",
                           "/.zen", "/.config/zen", "/.waterfox", "/.floorp", "/.config/floorp"}) {
    for (const QString &profile : subdirs(base + QString::fromLatin1(root)))
      addVersioned(found, profile + QStringLiteral("/gmp-widevinecdm"), QLatin1Char('/') + libraryName());
  }
}

QString findInstalledCdm() {
  QList<Candidate> found;
  for (const char *root : {"/opt/google/chrome", "/opt/google/chrome-beta", "/opt/google/chrome-unstable",
                           "/opt/microsoft/msedge", "/opt/microsoft/msedge-beta", "/opt/microsoft/msedge-dev",
                           "/opt/vivaldi", "/usr/lib/chromium", "/usr/lib64/chromium", "/usr/lib/chromium-browser",
                           "/usr/lib64/chromium-browser"})
    addBundled(found, QString::fromLatin1(root) + QStringLiteral("/WidevineCdm"));
  const QString home = QDir::homePath();
  addBundled(found, home + QStringLiteral("/.local/lib/vivaldi/WidevineCdm"));
  const QString xdgConfig = qEnvironmentVariable("XDG_CONFIG_HOME");
  addConfigDirs(found, xdgConfig.isEmpty() ? home + QStringLiteral("/.config") : xdgConfig);
  addGeckoRoots(found, home);
  addGeckoRoots(found, home + QStringLiteral("/snap/firefox/common"));
  for (const QString &app : subdirs(home + QStringLiteral("/.var/app"))) {
    addConfigDirs(found, app + QStringLiteral("/config"));
    addGeckoRoots(found, app);
  }
  return newest(found);
}
#endif
#endif

} // namespace

QString findWidevineCdm() {
  const QString chosen = qEnvironmentVariable("ORCHARD_WIDEVINE_PATH");
  if (!chosen.isEmpty())
    return QFileInfo::exists(chosen) ? chosen : QString();
  return findInstalledCdm();
}
