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

#include "apple_music_helper.h"
#include "apple_music_page.h"

#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>

#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
#include <QDir>
#include <QFileInfo>
#include <QSocketNotifier>
#include <QVersionNumber>
#include <QWebChannel>
#include <QWebEnginePage>
#include <QWebEngineProfile>
#include <QWebEngineScript>
#include <QWebEngineScriptCollection>
#include <QWebEngineSettings>

#include <algorithm>
#include <cstdio>
#include <unistd.h>
#include <utility>
#endif

using namespace apple_music_page;

#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
namespace {

// Wires the page's postMessage to the helper once QWebChannel connects.
constexpr auto kBridge = R"JS(
(() => {
  if (location.origin !== %1) return;
%2
  new QWebChannel(qt.webChannelTransport, (channel) => {
    const page = channel.objects.orchardPage;
    window.orchardAppleMusic = { postMessage: (message) => page.receive(String(message)) };
    window.dispatchEvent(new Event('orchardbridge'));
  });
})();
)JS";

QString widevineArch() {
#if defined(Q_PROCESSOR_ARM_64)
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

// Qt WebEngine ships no Widevine; borrow an installed browser's copy.
QString findWidevine() {
  const QString chosen = qEnvironmentVariable("ORCHARD_WIDEVINE_PATH");
  if (!chosen.isEmpty())
    return QFileInfo::exists(chosen) ? chosen : QString();
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

QString g_widevine;

void emitEvent(const QJsonObject &event) {
  const QByteArray line = QJsonDocument(event).toJson(QJsonDocument::Compact) + '\n';
  std::fwrite(line.constData(), 1, size_t(line.size()), stdout);
  std::fflush(stdout);
}

QWebEngineScript bridgeScript() {
  QFile channel(QStringLiteral(":/qtwebchannel/qwebchannel.js"));
  channel.open(QIODevice::ReadOnly);
  QWebEngineScript script;
  script.setName(QStringLiteral("orchard-apple-player-bridge"));
  script.setSourceCode(QString::fromLatin1(kBridge).arg(jsString(QString::fromLatin1(kOrigin)),
                                                        QString::fromUtf8(channel.readAll())));
  script.setInjectionPoint(QWebEngineScript::DocumentReady);
  script.setWorldId(QWebEngineScript::MainWorld);
  script.setRunsOnSubFrames(false);
  return script;
}

} // namespace
#endif

void prepareAppleMusicPlayer() {
#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
  g_widevine = findWidevine();
  QByteArray flags = qgetenv("QTWEBENGINE_CHROMIUM_FLAGS");
  // Audio needs no GPU process. A page with no view counts as hidden, and hidden
  // pages get slow timers that would stall position reports and gapless handoffs.
  flags += " --disable-gpu --disable-background-timer-throttling --disable-renderer-backgrounding";
  // Chromium splits these flags on spaces.
  if (!g_widevine.isEmpty() && !g_widevine.contains(QLatin1Char(' ')))
    flags += " --widevine-path=" + QFile::encodeName(g_widevine);
  qputenv("QTWEBENGINE_CHROMIUM_FLAGS", flags.trimmed());
#endif
}

int runAppleMusicPlayer() {
#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
  const QString pageHtml = html(QStringLiteral(":/auth/applemusic-player.html"));
  if (pageHtml.isEmpty())
    return 1;
  emitEvent({{QStringLiteral("type"), QStringLiteral("host")}, {QStringLiteral("widevine"), !g_widevine.isEmpty()}});

  QWebEngineProfile profile(QString::fromLatin1(kProfileName));
  profile.setPersistentCookiesPolicy(QWebEngineProfile::ForcePersistentCookies);
  profile.scripts()->insert(bridgeScript());
  QWebEnginePage page(&profile);
  page.settings()->setAttribute(QWebEngineSettings::PlaybackRequiresUserGesture, false);
  page.setVisible(true);
  AppleMusicPageBridge bridge([](const QString &raw) {
    const QJsonDocument message = QJsonDocument::fromJson(raw.toUtf8());
    if (message.isObject())
      emitEvent(message.object());
  });
  QWebChannel channel;
  channel.registerObject(QStringLiteral("orchardPage"), &bridge);
  page.setWebChannel(&channel, QWebEngineScript::MainWorld);
  QObject::connect(&page, &QWebEnginePage::renderProcessTerminated, qApp, [](auto, int) {
    emitEvent({{QStringLiteral("type"), QStringLiteral("error")}, {QStringLiteral("code"), QStringLiteral("crashed")}});
    QCoreApplication::exit(1);
  });

  bool loaded = false;
  QList<QString> pending;
  const auto deliver = [&page](const QString &command) {
    page.runJavaScript(QStringLiteral("window.orchardPlayer && window.orchardPlayer.command(%1)").arg(command));
  };
  QObject::connect(&page, &QWebEnginePage::loadFinished, qApp, [&](bool ok) {
    if (!ok) {
      emitEvent({{QStringLiteral("type"), QStringLiteral("error")}, {QStringLiteral("code"), QStringLiteral("page")}});
      QCoreApplication::exit(1);
      return;
    }
    loaded = true;
    for (const QString &command : std::exchange(pending, {}))
      deliver(command);
  });

  // Commands are re-serialized from parsed JSON so stdin can only ever pass an object literal.
  QByteArray input;
  QSocketNotifier notifier(STDIN_FILENO, QSocketNotifier::Read);
  QObject::connect(&notifier, &QSocketNotifier::activated, qApp, [&] {
    char chunk[16384];
    const ssize_t read = ::read(STDIN_FILENO, chunk, sizeof chunk);
    if (read <= 0) {
      notifier.setEnabled(false);
      QCoreApplication::quit();
      return;
    }
    input.append(chunk, read);
    if (input.size() > 1024 * 1024) {
      QCoreApplication::exit(1);
      return;
    }
    for (qsizetype end = input.indexOf('\n'); end >= 0; end = input.indexOf('\n')) {
      const QJsonDocument command = QJsonDocument::fromJson(input.left(end));
      input.remove(0, end + 1);
      if (!command.isObject())
        continue;
      const QString text = QString::fromUtf8(command.toJson(QJsonDocument::Compact));
      if (loaded)
        deliver(text);
      else
        pending.append(text);
    }
  });

  page.setHtml(pageHtml, QUrl(QString::fromLatin1(kPlayerUrl)));
  return QCoreApplication::exec();
#else
  return 2;
#endif
}
