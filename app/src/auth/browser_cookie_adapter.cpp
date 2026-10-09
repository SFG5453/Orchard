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

#include "browser_cookie_adapter.h"

#include <QCoreApplication>
#include <QDateTime>
#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
#include <QQuickWebEngineProfile>
#include <QWebEngineCookieStore>
#endif

BrowserCookieAdapter::BrowserCookieAdapter(QObject *parent) : QObject(parent) {
  m_notify.setSingleShot(true);
  m_notify.setInterval(300);
  connect(&m_notify, &QTimer::timeout, this, &BrowserCookieAdapter::cookiesChanged);
}

void BrowserCookieAdapter::start() {
  if (m_started)
    return;
  m_started = true;
#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
  auto *profile = QQuickWebEngineProfile::defaultProfile();
  // These are the same settings used by QtWebView's WebEngine plugin.
  profile->setStorageName(QCoreApplication::applicationName());
  profile->setOffTheRecord(false);
  auto *store = profile->cookieStore();
  connect(store, &QWebEngineCookieStore::cookieAdded, this,
          [this](const QNetworkCookie &cookie) {
    const QString domain = cookie.domain().toLower();
    if (domain != QStringLiteral(".youtube.com") &&
        domain != QStringLiteral("youtube.com") &&
        domain != QStringLiteral("music.youtube.com")) return;
    if (cookie.path() != QStringLiteral("/") && !cookie.path().isEmpty()) return;
    m_cookies.insert(cookie.name(), cookie);
    m_notify.start();
  });
  connect(store, &QWebEngineCookieStore::cookieRemoved, this,
          [this](const QNetworkCookie &cookie) {
    const auto found = m_cookies.constFind(cookie.name());
    if (found != m_cookies.cend() && found->hasSameIdentifier(cookie)
        && found->value() == cookie.value()) {
      m_cookies.remove(cookie.name());
      m_notify.start();
    }
  });
  store->loadAllCookies();
#endif
}

QString BrowserCookieAdapter::cookies() const {
  QStringList parts;
  for (const auto &cookie : m_cookies) {
    if (!cookie.isSessionCookie() && cookie.expirationDate() <= QDateTime::currentDateTimeUtc()) continue;
    parts.append(QString::fromLatin1(cookie.toRawForm(QNetworkCookie::NameAndValueOnly)));
  }
  return parts.join(QStringLiteral("; "));
}
