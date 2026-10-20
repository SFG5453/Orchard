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

#pragma once

#include <QObject>
#include <QMap>
#include <QNetworkCookie>
#include <QTimer>

// Reads YouTube cookies from the sign-in browser profile: WebKitGTK's cookie
// manager on Linux, WebView2's on Windows.
class BrowserCookieAdapter final : public QObject {
  Q_OBJECT
public:
  explicit BrowserCookieAdapter(QObject *parent = nullptr);
  void start();
  QString cookies() const;
signals:
  void cookiesChanged();
  void errorOccurred(const QString &message);
private:
  void readCookies();
  void apply(const QList<QNetworkCookie> &cookies);
  bool keep(const QNetworkCookie &cookie) const;

  QMap<QByteArray, QNetworkCookie> m_cookies;
  QTimer m_notify;
  QTimer m_poll;
  bool m_started{false};
  bool m_reading{false};
  // A change arrived during a read; read again after it.
  bool m_stale{false};
};
