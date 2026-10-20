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

#include "webkit_profile.h"
#include "webkit_async.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QHash>
#include <QStandardPaths>


using webkit_async::finished;
using webkit_async::pending;

namespace {

QNetworkCookie fromSoup(SoupCookie *soup) {
  QNetworkCookie cookie(soup_cookie_get_name(soup), soup_cookie_get_value(soup));
  cookie.setDomain(QString::fromUtf8(soup_cookie_get_domain(soup)));
  cookie.setPath(QString::fromUtf8(soup_cookie_get_path(soup)));
  cookie.setSecure(soup_cookie_get_secure(soup));
  cookie.setHttpOnly(soup_cookie_get_http_only(soup));
  if (GDateTime *expires = soup_cookie_get_expires(soup))
    cookie.setExpirationDate(QDateTime::fromSecsSinceEpoch(g_date_time_to_unix(expires), QTimeZone::UTC));
  return cookie;
}

bool under(const QString &name, const QStringList &domains) {
  for (const QString &domain : domains) {
    if (name == domain || name.endsWith(QLatin1Char('.') + domain))
      return true;
  }
  return false;
}

WebKitWebsiteDataManager *dataManager(WebKitWebContext *context) {
  return webkit_web_context_get_website_data_manager(context);
}

} // namespace

void initializeWebKit(int &argc, char **&argv) {
  gtk_init(&argc, &argv);
  // Matches the dark Qt pages these windows replace.
  g_object_set(gtk_settings_get_default(), "gtk-application-prefer-dark-theme", TRUE, nullptr);
}

WebKitProfile *WebKitProfile::named(const QString &name) {
  static QHash<QString, WebKitProfile *> profiles;
  WebKitProfile *&profile = profiles[name];
  if (!profile)
    profile = new WebKitProfile(name.isEmpty() ? QStringLiteral("default") : name, qApp);
  return profile;
}

WebKitProfile *WebKitProfile::ephemeral() {
  return new WebKitProfile(QString(), qApp);
}

WebKitProfile::WebKitProfile(const QString &directory, QObject *parent) : QObject(parent) {
  WebKitWebsiteDataManager *manager = nullptr;
  if (directory.isEmpty()) {
    manager = webkit_website_data_manager_new_ephemeral();
  } else {
    const QString data = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) +
                         QStringLiteral("/webkit/") + directory;
    const QString cache = QStandardPaths::writableLocation(QStandardPaths::CacheLocation) +
                          QStringLiteral("/webkit/") + directory;
    QDir().mkpath(data);
    manager = webkit_website_data_manager_new("base-data-directory", data.toUtf8().constData(),
                                              "base-cache-directory", cache.toUtf8().constData(), nullptr);
    webkit_cookie_manager_set_persistent_storage(webkit_website_data_manager_get_cookie_manager(manager),
                                                 (data + QStringLiteral("/cookies.sqlite")).toUtf8().constData(),
                                                 WEBKIT_COOKIE_PERSISTENT_STORAGE_SQLITE);
  }
  m_context = webkit_web_context_new_with_website_data_manager(manager);
  g_object_unref(manager);
  // Sign-in pages need no back-forward or page cache.
  webkit_web_context_set_cache_model(m_context, WEBKIT_CACHE_MODEL_DOCUMENT_VIEWER);
  g_signal_connect_swapped(webkit_web_context_get_cookie_manager(m_context), "changed",
                           G_CALLBACK(+[](WebKitProfile *self) { emit self->cookiesChanged(); }), this);
}

WebKitProfile::~WebKitProfile() {
  g_signal_handlers_disconnect_by_data(webkit_web_context_get_cookie_manager(m_context), this);
  g_object_unref(m_context);
}

void WebKitProfile::cookies(const QUrl &url, std::function<void(const QList<QNetworkCookie> &)> done) {
  WebKitCookieManager *manager = webkit_web_context_get_cookie_manager(m_context);
  webkit_cookie_manager_get_cookies(manager, url.toEncoded().constData(), nullptr, finished,
                                    pending(this, [done](GObject *source, GAsyncResult *result) {
    GList *list = webkit_cookie_manager_get_cookies_finish(WEBKIT_COOKIE_MANAGER(source), result, nullptr);
    QList<QNetworkCookie> cookies;
    for (GList *item = list; item; item = item->next)
      cookies.append(fromSoup(static_cast<SoupCookie *>(item->data)));
    g_list_free_full(list, reinterpret_cast<GDestroyNotify>(soup_cookie_free));
    done(cookies);
  }));
}

void WebKitProfile::setCookie(const QNetworkCookie &cookie, std::function<void()> done) {
  SoupCookie *soup = soup_cookie_new(cookie.name().constData(), cookie.value().constData(),
                                     cookie.domain().toUtf8().constData(),
                                     cookie.path().isEmpty() ? "/" : cookie.path().toUtf8().constData(), -1);
  soup_cookie_set_secure(soup, cookie.isSecure());
  soup_cookie_set_http_only(soup, cookie.isHttpOnly());
  if (!cookie.isSessionCookie()) {
    GDateTime *expires = g_date_time_new_from_unix_utc(cookie.expirationDate().toSecsSinceEpoch());
    soup_cookie_set_expires(soup, expires);
    g_date_time_unref(expires);
  }
  webkit_cookie_manager_add_cookie(webkit_web_context_get_cookie_manager(m_context), soup, nullptr, finished,
                                   pending(this, [done](GObject *source, GAsyncResult *result) {
    webkit_cookie_manager_add_cookie_finish(WEBKIT_COOKIE_MANAGER(source), result, nullptr);
    if (done)
      done();
  }));
  soup_cookie_free(soup);
}

void WebKitProfile::forget(const QStringList &domains, std::function<void()> done) {
  webkit_website_data_manager_fetch(dataManager(m_context), WEBKIT_WEBSITE_DATA_ALL, nullptr, finished,
                                    pending(this, [this, domains, done](GObject *source, GAsyncResult *result) {
    auto *manager = WEBKIT_WEBSITE_DATA_MANAGER(source);
    GList *all = webkit_website_data_manager_fetch_finish(manager, result, nullptr);
    GList *matching = nullptr;
    for (GList *item = all; item; item = item->next) {
      auto *data = static_cast<WebKitWebsiteData *>(item->data);
      if (under(QString::fromUtf8(webkit_website_data_get_name(data)), domains))
        matching = g_list_prepend(matching, data);
    }
    // `matching` borrows from `all`, which outlives the removal.
    const auto release = [all, matching] {
      g_list_free(matching);
      g_list_free_full(all, reinterpret_cast<GDestroyNotify>(webkit_website_data_unref));
    };
    if (!matching) {
      release();
      done();
      return;
    }
    webkit_website_data_manager_remove(manager, WEBKIT_WEBSITE_DATA_ALL, matching, nullptr, finished,
                                       pending(this, [release, done](GObject *source, GAsyncResult *result) {
      webkit_website_data_manager_remove_finish(WEBKIT_WEBSITE_DATA_MANAGER(source), result, nullptr);
      release();
      done();
    }));
  }));
}

void WebKitProfile::clear(std::function<void(bool ok)> done) {
  webkit_website_data_manager_clear(dataManager(m_context), WEBKIT_WEBSITE_DATA_ALL, 0, nullptr, finished,
                                    pending(this, [done](GObject *source, GAsyncResult *result) {
    done(webkit_website_data_manager_clear_finish(WEBKIT_WEBSITE_DATA_MANAGER(source), result, nullptr));
  }));
}
