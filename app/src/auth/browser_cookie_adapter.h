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

// Reads the cookie store already owned by the QtWebView login backend.
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
  QMap<QByteArray, QNetworkCookie> m_cookies;
  QTimer m_notify;
  bool m_started{false};
};
