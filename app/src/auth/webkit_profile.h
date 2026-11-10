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

#pragma once

#include <QList>
#include <QNetworkCookie>
#include <QObject>
#include <QString>
#include <QStringList>
#include <QUrl>

#include <functional>
#include <memory>

#ifndef __APPLE__
typedef struct _WebKitWebContext WebKitWebContext;
#endif

// Call before QGuiApplication. On Linux it starts GTK, and Qt's GLib event loop
// then runs GTK and WebKit as well.
void initializeWebKit(int &argc, char **&argv);

// A WebKitGTK or WKWebView browser profile: cookies, storage and cache kept by
// the platform's web engine, or in memory only.
class WebKitProfile final : public QObject {
  Q_OBJECT

public:
  // The sign-in profile shared by Google and Spotify when `name` is empty.
  static WebKitProfile *named(const QString &name = {});
  // Keeps nothing once the process ends.
  static WebKitProfile *ephemeral();

  ~WebKitProfile() override;

#ifdef __APPLE__
  struct Native;
  Native *native() const { return m_native.get(); }
#else
  WebKitWebContext *context() const { return m_context; }
#endif

  // Cookies the profile would send to `url`, HttpOnly ones included.
  void cookies(const QUrl &url, std::function<void(const QList<QNetworkCookie> &)> done);
  void setCookie(const QNetworkCookie &cookie, std::function<void()> done = {});
  // Removes cookies and site data of every domain equal to or under one of `domains`.
  void forget(const QStringList &domains, std::function<void()> done);
  // Removes all cookies, storage and cache.
  void clear(std::function<void(bool ok)> done);

signals:
  void cookiesChanged();

private:
  WebKitProfile(const QString &directory, QObject *parent);

#ifdef __APPLE__
  std::unique_ptr<Native> m_native;
#else
  WebKitWebContext *m_context{nullptr};
#endif
};
