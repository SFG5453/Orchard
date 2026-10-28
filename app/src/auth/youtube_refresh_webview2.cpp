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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "youtube_refresh.h"

#include "auth_diagnostics.h"
#include "auth_manager.h"
#include "auth_session_channel.h"
#include "webview2_view.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStringList>
#include <QTimer>
#include <QUrlQuery>

namespace {

constexpr int kPollMs = 500;
constexpr int kTimeoutMs = 45000;
// A logged-out page is only believed after a few reads; ytcfg can arrive late.
constexpr int kSignedOutReads = 4;

// The same page values the sign-in capture reads, plus whether YouTube thinks we are signed in.
constexpr char kReadConfig[] = R"JS(
(function() {
  var config = window.ytcfg;
  if (!config || typeof config.get !== 'function') return JSON.stringify({ ready: false });
  var get = function(key) { return config.get(key); };
  return JSON.stringify({
    ready: !!get('INNERTUBE_CLIENT_VERSION'),
    loggedIn: get('LOGGED_IN') === true,
    visitorData: get('VISITOR_DATA') || '',
    dataSyncId: get('DATASYNC_ID') || '',
    delegatedSessionId: get('DELEGATED_SESSION_ID') || '',
    accountIndex: Number(get('SESSION_INDEX') || 0),
    clientVersion: get('INNERTUBE_CLIENT_VERSION') || ''
  });
})();
)JS";

bool keep(const QNetworkCookie &cookie) {
  const QString domain = cookie.domain().toLower();
  if (domain != QStringLiteral(".youtube.com") && domain != QStringLiteral("youtube.com") &&
      domain != QStringLiteral("music.youtube.com"))
    return false;
  if (!cookie.isSessionCookie() && cookie.expirationDate() <= QDateTime::currentDateTimeUtc())
    return false;
  return cookie.path() == QStringLiteral("/") || cookie.path().isEmpty();
}

QString cookieHeader(const QList<QNetworkCookie> &cookies) {
  QStringList parts;
  for (const auto &cookie : cookies)
    if (keep(cookie))
      parts.append(QString::fromLatin1(cookie.toRawForm(QNetworkCookie::NameAndValueOnly)));
  return parts.join(QStringLiteral("; "));
}

// The parent sends {"accountIndex": n} on stdin so the right Google account loads.
int requestedAccountIndex() {
  QFile input;
  if (!input.open(stdin, QIODevice::ReadOnly))
    return 0;
  const QJsonObject request = QJsonDocument::fromJson(input.readLine(4096)).object();
  return qMax(0, request.value(QStringLiteral("accountIndex")).toInt());
}

} // namespace

int runYouTubeRefresh(const QString &socketName) {
  const int accountIndex = requestedAccountIndex();
  authDiagnostic(QStringLiteral("refresh-start account=%1").arg(accountIndex));
  WebView2HiddenHost host;
  // The default profile is the one Google sign-in used; its cookies are the live session.
  WebView2View page(WebView2View::Options{});
  QObject::connect(&page, &WebView2View::failed, qApp, [](const QString &message) {
    authDiagnostic(QStringLiteral("refresh-webview-failed %1").arg(message));
    QCoreApplication::exit(3);
  });
  QUrl url(QStringLiteral("https://music.youtube.com/"));
  if (accountIndex > 0)
    url.setQuery(QStringLiteral("authuser=%1").arg(accountIndex));
  page.navigate(url);
  page.setBounds(QRect(0, 0, 1280, 800));
  page.create(host.winId());

  bool busy = false;
  bool sent = false;
  int signedOutReads = 0;
  QTimer poll;
  poll.setInterval(kPollMs);
  QObject::connect(&poll, &QTimer::timeout, qApp, [&] {
    if (busy || sent || !page.isReady() || page.loading())
      return;
    busy = true;
    page.runJavaScript(QString::fromLatin1(kReadConfig), [&](const QJsonValue &value) {
      const QJsonObject config = QJsonDocument::fromJson(value.toString().toUtf8()).object();
      if (!config.value(QStringLiteral("ready")).toBool()) {
        busy = false;
        return;
      }
      if (!config.value(QStringLiteral("loggedIn")).toBool()) {
        busy = false;
        if (++signedOutReads >= kSignedOutReads) {
          authDiagnostic(QStringLiteral("refresh-profile-signed-out"));
          QCoreApplication::exit(2);
        }
        return;
      }
      page.cookies(QUrl(QStringLiteral("https://music.youtube.com/")), [&, config](const QList<QNetworkCookie> &cookies) {
        const QString cookie = AuthManager::normalizeYouTubeAuthCookie(cookieHeader(cookies));
        if (sent || !AuthManager::hasYouTubeLoginCookie(cookie)) {
          busy = false;
          return;
        }
        sent = true;
        poll.stop();
        authDiagnostic(QStringLiteral("refresh-session-read"));
        sendSessionAndQuit(socketName, {{QStringLiteral("cookie"), cookie},
                                        {QStringLiteral("visitorData"), config.value(QStringLiteral("visitorData"))},
                                        {QStringLiteral("dataSyncId"), config.value(QStringLiteral("dataSyncId"))},
                                        {QStringLiteral("delegatedSessionId"), config.value(QStringLiteral("delegatedSessionId"))},
                                        {QStringLiteral("accountIndex"), config.value(QStringLiteral("accountIndex"))},
                                        {QStringLiteral("clientVersion"), config.value(QStringLiteral("clientVersion"))}});
      });
    });
  });
  poll.start();
  QTimer::singleShot(kTimeoutMs, qApp, [] {
    authDiagnostic(QStringLiteral("refresh-timeout"));
    QCoreApplication::exit(3);
  });
  return QCoreApplication::exec();
}
