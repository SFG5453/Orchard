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

#include "spotify_helper.h"
#include "auth_session_channel.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTimer>

#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
#include <QNetworkCookie>
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickView>
#include <QQuickWebEngineProfile>
#include <QWebEngineCookieStore>
#include <QWebEnginePage>
#include <QWebEngineProfile>
#include <QWebEngineScript>
#include <QWebEngineScriptCollection>
#endif

namespace {

// Spotify serves its web player to current desktop browsers only.
constexpr auto kBrowserUserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0";
constexpr int kTokenTimeoutMs = 25000;

void sendAndQuit(const QString &socketName, const QJsonObject &result) {
  auto *client = new AuthSessionClient(qApp);
  QObject::connect(client, &AuthSessionClient::finished, qApp,
                   [](bool accepted) { QCoreApplication::exit(accepted ? 0 : 1); });
  client->send(socketName, result);
}

#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
bool isSpotifyCookie(const QNetworkCookie &cookie) {
  const QString domain = cookie.domain().toLower();
  return domain == QStringLiteral("spotify.com") || domain.endsWith(QStringLiteral(".spotify.com"));
}

// The web player trades its cookie for a signed token; read that answer as it
// arrives instead of forging Spotify's request signature. Runs before page code.
constexpr auto kTokenHook = R"JS(
(() => {
  const keep = (data) => {
    window.__orchardSpotifyTokenSeen = data && data.isAnonymous === true ? 'anonymous' : 'signed-in';
    if (data && data.accessToken && data.isAnonymous !== true)
      window.__orchardSpotifyToken = {
        accessToken: data.accessToken,
        expiresAt: data.accessTokenExpirationTimestampMs || 0
      };
  };
  const isToken = (url) => /\/api\/token(?:\?|$)/.test(String(url || ''));
  // The player passes a URL object, which has no .url; String() gives its href.
  const urlOf = (input) => typeof input === 'string' ? input : (input && input.url) || String(input);
  const originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function (input) {
      const pending = originalFetch.apply(this, arguments);
      try {
        if (isToken(urlOf(input)))
          pending.then((response) => response.clone().json()).then(keep, () => {});
      } catch (_) {}
      return pending;
    };
  }
  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    if (isToken(url))
      this.addEventListener('load', () => { try { keep(JSON.parse(this.responseText)); } catch (_) {} });
    return open.apply(this, arguments);
  };
})();
)JS";

// Older players embedded the first token in the page instead of fetching it.
constexpr auto kTokenRead = R"JS(
JSON.stringify(window.__orchardSpotifyToken || (() => {
  try {
    const node = document.getElementById('session');
    const data = node && JSON.parse(node.textContent);
    return data && data.accessToken && data.isAnonymous !== true
      ? { accessToken: data.accessToken, expiresAt: data.accessTokenExpirationTimestampMs || 0 }
      : null;
  } catch (_) {
    return null;
  }
})())
)JS";
#endif

} // namespace

int runSpotifyLogin(const QString &socketName) {
#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
  // QtWebView's WebEngine backend owns this profile; match its settings.
  auto *profile = QQuickWebEngineProfile::defaultProfile();
  profile->setStorageName(QCoreApplication::applicationName());
  profile->setOffTheRecord(false);
  auto *store = profile->cookieStore();

  QQuickView view;
  view.setResizeMode(QQuickView::SizeRootObjectToView);
  view.resize(600, 760);
  view.setTitle(QObject::tr("Log in to Spotify"));
  view.setSource(QUrl(QStringLiteral("qrc:/auth/SpotifyLoginView.qml")));
  if (view.status() == QQuickView::Error || !view.rootObject())
    return 1;
  QObject::connect(view.rootObject(), SIGNAL(closeRequested()), qApp, SLOT(quit()));

  bool sent = false;
  QObject::connect(store, &QWebEngineCookieStore::cookieAdded, qApp,
                   [store, socketName, &sent, &view](const QNetworkCookie &cookie) {
    if (sent || cookie.name() != "sp_dc" || !isSpotifyCookie(cookie) || cookie.value().isEmpty())
      return;
    sent = true;
    view.hide();
    // The keychain keeps sp_dc; the shared browser profile forgets Spotify.
    QObject::connect(store, &QWebEngineCookieStore::cookieAdded, qApp,
                     [store](const QNetworkCookie &other) {
      if (isSpotifyCookie(other))
        store->deleteCookie(other);
    });
    store->deleteCookie(cookie);
    store->loadAllCookies();
    sendAndQuit(socketName, {{QStringLiteral("spdc"), QString::fromLatin1(cookie.value())}});
  });
  store->loadAllCookies();
  view.show();
  return QCoreApplication::exec();
#else
  Q_UNUSED(socketName);
  return 2;
#endif
}

int runSpotifyToken(const QString &socketName) {
  QFile input;
  if (!input.open(stdin, QIODevice::ReadOnly))
    return 1;
  const QString spdc =
      QJsonDocument::fromJson(input.readLine(16 * 1024)).object().value(QStringLiteral("spdc")).toString();
  if (spdc.isEmpty())
    return 1;
#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
  // Off the record: each harvest starts from the stored cookie and leaves nothing behind.
  QWebEngineProfile profile;
  profile.setHttpUserAgent(QString::fromLatin1(kBrowserUserAgent));
  QWebEnginePage page(&profile);
  QWebEngineScript hook;
  hook.setName(QStringLiteral("orchard-spotify-token"));
  hook.setSourceCode(QString::fromLatin1(kTokenHook));
  hook.setInjectionPoint(QWebEngineScript::DocumentCreation);
  hook.setWorldId(QWebEngineScript::MainWorld);
  hook.setRunsOnSubFrames(false);
  page.scripts().insert(hook);

  QNetworkCookie cookie("sp_dc", spdc.toLatin1());
  cookie.setDomain(QStringLiteral(".spotify.com"));
  cookie.setPath(QStringLiteral("/"));
  cookie.setSecure(true);
  cookie.setHttpOnly(true);
  cookie.setExpirationDate(QDateTime::currentDateTimeUtc().addYears(1));
  bool loading = false;
  const auto load = [&page, &loading] {
    if (loading)
      return;
    loading = true;
    page.load(QUrl(QStringLiteral("https://open.spotify.com/")));
  };
  QObject::connect(profile.cookieStore(), &QWebEngineCookieStore::cookieAdded, qApp,
                   [load](const QNetworkCookie &added) {
    if (added.name() == "sp_dc")
      load();
  });
  profile.cookieStore()->setCookie(cookie, QUrl(QStringLiteral("https://open.spotify.com")));
  QTimer::singleShot(1000, qApp, load);

  bool sent = false;
  QTimer poll;
  poll.setInterval(400);
  QObject::connect(&poll, &QTimer::timeout, qApp, [&page, &sent, &poll, socketName] {
    page.runJavaScript(QString::fromLatin1(kTokenRead), [&sent, &poll, socketName](const QVariant &value) {
      const QJsonObject token = QJsonDocument::fromJson(value.toString().toUtf8()).object();
      if (sent || token.value(QStringLiteral("accessToken")).toString().isEmpty())
        return;
      sent = true;
      poll.stop();
      sendAndQuit(socketName, token);
    });
  });
  poll.start();
  // No token in time: exit quietly and let the parent try again later.
  QTimer::singleShot(kTokenTimeoutMs, qApp, [&page] {
    if (!qEnvironmentVariableIsSet("ORCHARD_SPOTIFY_DEBUG")) {
      QCoreApplication::exit(3);
      return;
    }
    // Tells a dead cookie (anonymous token) apart from a player that never asked.
    page.runJavaScript(QStringLiteral("String(window.__orchardSpotifyTokenSeen || 'none')"),
                       [](const QVariant &seen) {
      qInfo().noquote() << "Spotify token response:" << seen.toString();
      QCoreApplication::exit(3);
    });
  });
  return QCoreApplication::exec();
#else
  Q_UNUSED(socketName);
  return 2;
#endif
}
