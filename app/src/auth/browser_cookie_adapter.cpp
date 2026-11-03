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

#include <QDateTime>
#include <QPointer>
#include <QUrl>
#include <utility>
#ifdef ORCHARD_WEBKIT_AUTH
#include "webkit_profile.h"
#endif
#ifdef ORCHARD_WEBVIEW2_AUTH
#include "webview2_view.h"
#endif

BrowserCookieAdapter::BrowserCookieAdapter(QObject *parent) : QObject(parent) {
  m_notify.setSingleShot(true);
  m_notify.setInterval(300);
  connect(&m_notify, &QTimer::timeout, this, &BrowserCookieAdapter::cookiesChanged);
  // WebView2 has no cookie change event.
  m_poll.setInterval(500);
  connect(&m_poll, &QTimer::timeout, this, &BrowserCookieAdapter::readCookies);
}

bool BrowserCookieAdapter::keep(const QNetworkCookie &cookie) const {
  const QString domain = cookie.domain().toLower();
  if (domain != QStringLiteral(".youtube.com") && domain != QStringLiteral("youtube.com") &&
      domain != QStringLiteral("music.youtube.com"))
    return false;
  return cookie.path() == QStringLiteral("/") || cookie.path().isEmpty();
}

void BrowserCookieAdapter::start() {
  if (m_started)
    return;
  m_started = true;
#ifdef ORCHARD_WEBKIT_AUTH
  connect(WebKitProfile::named(), &WebKitProfile::cookiesChanged, this, &BrowserCookieAdapter::readCookies);
#elif defined(ORCHARD_WEBVIEW2_AUTH)
  m_poll.start();
#endif
  readCookies();
}

void BrowserCookieAdapter::readCookies() {
  const QPointer<BrowserCookieAdapter> self(this);
  const auto receive = [self](const QList<QNetworkCookie> &cookies) {
    if (!self)
      return;
    self->m_reading = false;
    self->apply(cookies);
    if (std::exchange(self->m_stale, false))
      self->readCookies();
  };
  const QUrl url(QStringLiteral("https://music.youtube.com/"));
#ifdef ORCHARD_WEBKIT_AUTH
  if (m_reading) {
    m_stale = true;
    return;
  }
  m_reading = true;
  WebKitProfile::named()->cookies(url, receive);
#elif defined(ORCHARD_WEBVIEW2_AUTH)
  // The sign-in page's view owns the default profile; read its live cookie manager.
  WebView2View *view = WebView2View::findReady(QString());
  if (!view || m_reading)
    return;
  m_reading = true;
  view->cookies(url, receive);
#else
  Q_UNUSED(receive);
  Q_UNUSED(url);
#endif
}

void BrowserCookieAdapter::apply(const QList<QNetworkCookie> &cookies) {
  QMap<QByteArray, QNetworkCookie> snapshot;
  for (const auto &cookie : cookies)
    if (keep(cookie))
      snapshot.insert(cookie.name(), cookie);
  bool same = snapshot.size() == m_cookies.size();
  for (auto it = snapshot.cbegin(); same && it != snapshot.cend(); ++it)
    same = m_cookies.value(it.key()).value() == it->value();
  if (same)
    return;
  m_cookies = snapshot;
  m_notify.start();
}

QString BrowserCookieAdapter::cookies() const {
  QStringList parts;
  for (const auto &cookie : m_cookies) {
    if (!cookie.isSessionCookie() && cookie.expirationDate() <= QDateTime::currentDateTimeUtc()) continue;
    parts.append(QString::fromLatin1(cookie.toRawForm(QNetworkCookie::NameAndValueOnly)));
  }
  return parts.join(QStringLiteral("; "));
}
