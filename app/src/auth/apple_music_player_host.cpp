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
#include "apple_music_assets.h"
#include "apple_music_page.h"
#include "playback/widevine_path.h"

#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>

#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
#include <QFile>
#include <QSocketNotifier>
#include <QWebChannel>
#include <QWebEnginePage>
#include <QWebEngineProfile>
#include <QWebEngineScript>
#include <QWebEngineScriptCollection>
#include <QWebEngineSettings>
#include <QWebEngineUrlRequestJob>
#include <QWebEngineUrlScheme>
#include <QWebEngineUrlSchemeHandler>

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

QString g_widevine;

void emitEvent(const QJsonObject &event) {
  const QByteArray line = QJsonDocument(event).toJson(QJsonDocument::Compact) + '\n';
  std::fwrite(line.constData(), 1, size_t(line.size()), stdout);
  std::fflush(stdout);
}

// Serves the scan fallback's allow-listed files to the page's origin.
class AssetHandler final : public QWebEngineUrlSchemeHandler {
public:
  void requestStarted(QWebEngineUrlRequestJob *job) override {
    const QString name = job->requestUrl().path().mid(1);
    auto *file = new QFile(apple_music_assets::path(name), job);
    if (job->requestUrl().host() != QStringLiteral("assets") || !file->open(QIODevice::ReadOnly)) {
      job->fail(QWebEngineUrlRequestJob::UrlNotFound);
      return;
    }
    job->setAdditionalResponseHeaders({{"Access-Control-Allow-Origin", kOrigin}});
    const QByteArray type = name.endsWith(QStringLiteral(".mjs"))    ? "text/javascript"
                            : name.endsWith(QStringLiteral(".wasm")) ? "application/wasm"
                                                                     : "application/octet-stream";
    job->reply(type, file);
  }
};

QWebEngineScript bridgeScript() {
  QFile channel(QStringLiteral(":/qtwebchannel/qwebchannel.js"));
  if (!channel.open(QIODevice::ReadOnly))
    qWarning("Apple Music player: qwebchannel.js is missing");
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
  g_widevine = findWidevineCdm();
  QByteArray flags = qgetenv("QTWEBENGINE_CHROMIUM_FLAGS");
  // Audio needs no GPU process. A page with no view counts as hidden, and hidden
  // pages get slow timers that would stall position reports and gapless handoffs.
  flags += " --disable-gpu --disable-background-timer-throttling --disable-renderer-backgrounding";
  // Chromium splits these flags on spaces.
  if (!g_widevine.isEmpty() && !g_widevine.contains(QLatin1Char(' ')))
    flags += " --widevine-path=" + QFile::encodeName(g_widevine);
  qputenv("QTWEBENGINE_CHROMIUM_FLAGS", flags.trimmed());
  // Schemes register before the application exists.
  QWebEngineUrlScheme scheme(apple_music_assets::kScheme);
  scheme.setSyntax(QWebEngineUrlScheme::Syntax::Host);
  scheme.setFlags(QWebEngineUrlScheme::SecureScheme | QWebEngineUrlScheme::CorsEnabled |
                  QWebEngineUrlScheme::FetchApiAllowed);
  QWebEngineUrlScheme::registerScheme(scheme);
#endif
}

int runAppleMusicPlayer() {
#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
  const QString pageHtml = playerHtml();
  if (pageHtml.isEmpty())
    return 1;
  emitEvent({{QStringLiteral("type"), QStringLiteral("host")},
             {QStringLiteral("widevine"), !g_widevine.isEmpty()},
             {QStringLiteral("scan"), apple_music_assets::available()}});

  QWebEngineProfile profile(QString::fromLatin1(kProfileName));
  profile.setPersistentCookiesPolicy(QWebEngineProfile::ForcePersistentCookies);
  profile.scripts()->insert(bridgeScript());
  AssetHandler assets;
  profile.installUrlSchemeHandler(apple_music_assets::kScheme, &assets);
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
  // Bytes of `input` already searched for a newline; a mix plan arrives as one multi-megabyte line.
  qsizetype scanned = 0;
  QSocketNotifier notifier(STDIN_FILENO, QSocketNotifier::Read);
  QObject::connect(&notifier, &QSocketNotifier::activated, qApp, [&] {
    char chunk[65536];
    const ssize_t read = ::read(STDIN_FILENO, chunk, sizeof chunk);
    if (read <= 0) {
      notifier.setEnabled(false);
      QCoreApplication::quit();
      return;
    }
    input.append(chunk, read);
    if (input.size() > 32 * 1024 * 1024) {
      QCoreApplication::exit(1);
      return;
    }
    for (qsizetype end = input.indexOf('\n', scanned); end >= 0; end = input.indexOf('\n')) {
      scanned = 0;
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
    scanned = input.size();
  });

  page.setHtml(pageHtml, QUrl(QString::fromLatin1(kPlayerUrl)));
  return QCoreApplication::exec();
#else
  return 2;
#endif
}
