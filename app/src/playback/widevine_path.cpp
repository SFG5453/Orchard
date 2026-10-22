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
#include <QFileInfo>
#include <QVersionNumber>

#include <algorithm>

namespace {

QString widevineArch() {
#if defined(Q_OS_WIN) && defined(Q_PROCESSOR_ARM_64)
  return QStringLiteral("win_arm64");
#elif defined(Q_OS_WIN)
  return QStringLiteral("win_x64");
#elif defined(Q_PROCESSOR_ARM_64)
  return QStringLiteral("linux_arm64");
#else
  return QStringLiteral("linux_x64");
#endif
}

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

#ifdef Q_OS_WIN
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
QString findInstalledCdm() {
  const QString bundled = QStringLiteral("/_platform_specific/") + widevineArch() + QStringLiteral("/libwidevinecdm.so");
  for (const char *root : {"/opt/google/chrome/WidevineCdm", "/opt/google/chrome-beta/WidevineCdm",
                           "/opt/google/chrome-unstable/WidevineCdm", "/usr/lib/chromium/WidevineCdm",
                           "/usr/lib64/chromium/WidevineCdm", "/usr/lib/chromium-browser/WidevineCdm"}) {
    const QString path = QString::fromLatin1(root) + bundled;
    if (QFileInfo::exists(path))
      return path;
  }
  const QString home = QDir::homePath();
  for (const char *root : {"/.config/google-chrome/WidevineCdm", "/.config/chromium/WidevineCdm",
                           "/.config/BraveSoftware/Brave-Browser/WidevineCdm",
                           "/.var/app/com.google.Chrome/config/google-chrome/WidevineCdm"}) {
    if (const QString path = newestCdm(home + QString::fromLatin1(root), bundled); !path.isEmpty())
      return path;
  }
  const QDir firefox(home + QStringLiteral("/.mozilla/firefox"));
  for (const QString &profile : firefox.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
    const QString path = newestCdm(firefox.filePath(profile) + QStringLiteral("/gmp-widevinecdm"),
                                   QStringLiteral("/libwidevinecdm.so"));
    if (!path.isEmpty())
      return path;
  }
  return {};
}
#endif

} // namespace

QString findWidevineCdm() {
  const QString chosen = qEnvironmentVariable("ORCHARD_WIDEVINE_PATH");
  if (!chosen.isEmpty())
    return QFileInfo::exists(chosen) ? chosen : QString();
  return findInstalledCdm();
}
