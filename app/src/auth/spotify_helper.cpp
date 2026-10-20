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
#include "spotify_page.h"

#include <QCoreApplication>
#include <QDebug>
#include <QJsonObject>

#ifdef ORCHARD_WEBKIT_AUTH
#include "webkit_profile.h"
#include "webkit_view.h"

#include <QDateTime>
#include <QNetworkCookie>
#include <QTimer>
#include <utility>
#endif

using namespace spotify_page;

int runSpotifyLogin(const QString &socketName) {
#ifdef ORCHARD_WEBKIT_AUTH
  WebKitProfile *profile = WebKitProfile::named();
  WebKitView view;
  QObject::connect(&view, &WebKitView::closeRequested, qApp, &QCoreApplication::quit);

  bool sent = false;
  bool reading = false;
  bool stale = false;
  std::function<void()> check = [&] {
    if (sent)
      return;
    if (reading) {
      stale = true;
      return;
    }
    reading = true;
    profile->cookies(QUrl(QStringLiteral("https://open.spotify.com/")), [&](const QList<QNetworkCookie> &cookies) {
      reading = false;
      for (const auto &cookie : cookies) {
        if (sent || cookie.name() != "sp_dc" || cookie.value().isEmpty())
          continue;
        sent = true;
        view.hide();
        // The keychain keeps sp_dc; the shared browser profile forgets Spotify.
        const QString spdc = QString::fromLatin1(cookie.value());
        profile->forget({QStringLiteral("spotify.com")}, [socketName, spdc] {
          sendSessionAndQuit(socketName, {{QStringLiteral("spdc"), spdc}});
        });
        return;
      }
      if (std::exchange(stale, false))
        check();
    });
  };
  QObject::connect(profile, &WebKitProfile::cookiesChanged, qApp, [&check] { check(); });
  view.setUrl(QUrl(QStringLiteral("https://accounts.spotify.com/en/login")));
  view.show(QObject::tr("Log in to Spotify"), QSize(600, 760), false);
  check();
  return QCoreApplication::exec();
#else
  Q_UNUSED(socketName);
  return 2;
#endif
}

int runSpotifyToken(const QString &socketName) {
  const QString spdc = readCookie();
  if (spdc.isEmpty())
    return 1;
#ifdef ORCHARD_WEBKIT_AUTH
  // Ephemeral: each harvest starts from the stored cookie and leaves nothing behind.
  WebKitProfile *profile = WebKitProfile::ephemeral();
  WebKitView::Options options;
  options.profile = profile;
  options.userAgent = QString::fromLatin1(kBrowserUserAgent);
  options.startupScripts = {QString::fromLatin1(kTokenHook)};
  WebKitView page(options);
  page.showOffscreen(QSize(1280, 800));

  QNetworkCookie cookie("sp_dc", spdc.toLatin1());
  cookie.setDomain(QStringLiteral(".spotify.com"));
  cookie.setPath(QStringLiteral("/"));
  cookie.setSecure(true);
  cookie.setHttpOnly(true);
  cookie.setExpirationDate(QDateTime::currentDateTimeUtc().addYears(1));
  profile->setCookie(cookie, [&page] { page.setUrl(QUrl(QStringLiteral("https://open.spotify.com/"))); });

  bool sent = false;
  QTimer poll;
  poll.setInterval(400);
  QObject::connect(&poll, &QTimer::timeout, qApp, [&page, &sent, &poll, socketName] {
    page.evaluate(QString::fromLatin1(kTokenRead), [&sent, &poll, socketName](const QString &value) {
      const QJsonObject token = QJsonDocument::fromJson(value.toUtf8()).object();
      if (sent || token.value(QStringLiteral("accessToken")).toString().isEmpty())
        return;
      sent = true;
      poll.stop();
      sendSessionAndQuit(socketName, token);
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
    page.evaluate(QStringLiteral("String(window.__orchardSpotifyTokenSeen || 'none')"), [](const QString &seen) {
      qInfo().noquote() << "Spotify token response:" << seen;
      QCoreApplication::exit(3);
    });
  });
  return QCoreApplication::exec();
#else
  Q_UNUSED(socketName);
  return 2;
#endif
}
