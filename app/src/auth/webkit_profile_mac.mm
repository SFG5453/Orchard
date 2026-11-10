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

#include "webkit_mac.h"
#include "webkit_profile.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QHash>
#include <QPointer>
#include <QUuid>

using webkit_mac::ns;

@interface OrchardCookieObserver : NSObject <WKHTTPCookieStoreObserver>
@property(nonatomic, copy) void (^changed)(void);
@end

@implementation OrchardCookieObserver
- (void)cookiesDidChangeInCookieStore:(WKHTTPCookieStore *)cookieStore {
  if (self.changed)
    self.changed();
}
@end

namespace {

// Names map to stable data store identifiers.
const QUuid kProfileNamespace(QStringLiteral("{6f1c2a0e-5b8d-4c47-9a3e-0d7b2f9e4c11}"));

QNetworkCookie fromNS(NSHTTPCookie *ns) {
  QNetworkCookie cookie(QString::fromNSString(ns.name).toUtf8(), QString::fromNSString(ns.value).toUtf8());
  cookie.setDomain(QString::fromNSString(ns.domain));
  cookie.setPath(QString::fromNSString(ns.path));
  cookie.setSecure(ns.isSecure);
  cookie.setHttpOnly(ns.isHTTPOnly);
  if (ns.expiresDate)
    cookie.setExpirationDate(QDateTime::fromNSDate(ns.expiresDate));
  return cookie;
}

// RFC 6265 domain and path matching, as the engine applies when it sends cookies to `url`.
bool sentTo(NSHTTPCookie *cookie, const QUrl &url) {
  const QString host = url.host().toLower();
  QString domain = QString::fromNSString(cookie.domain).toLower();
  if (domain.startsWith(QLatin1Char('.'))) {
    domain.remove(0, 1);
    if (host != domain && !host.endsWith(QLatin1Char('.') + domain))
      return false;
  } else if (host != domain) {
    return false;
  }
  if (cookie.isSecure && url.scheme() != QStringLiteral("https"))
    return false;
  const QString path = url.path().isEmpty() ? QStringLiteral("/") : url.path();
  const QString prefix = QString::fromNSString(cookie.path);
  if (prefix.isEmpty() || prefix == path)
    return true;
  return path.startsWith(prefix) && (prefix.endsWith(QLatin1Char('/')) || path.at(prefix.size()) == QLatin1Char('/'));
}

bool under(const QString &name, const QStringList &domains) {
  for (const QString &domain : domains) {
    if (name == domain || name.endsWith(QLatin1Char('.') + domain))
      return true;
  }
  return false;
}

WKWebsiteDataStore *persistentStore(const QString &name) {
  const QUuid id = QUuid::createUuidV5(kProfileNamespace, name);
  NSUUID *identifier = [[NSUUID alloc] initWithUUIDString:ns(id.toString(QUuid::WithoutBraces))];
  return [WKWebsiteDataStore dataStoreForIdentifier:identifier];
}

} // namespace

void initializeWebKit(int &, char **&) {}

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

WebKitProfile::WebKitProfile(const QString &directory, QObject *parent)
    : QObject(parent), m_native(std::make_unique<Native>()) {
  m_native->store = directory.isEmpty() ? [WKWebsiteDataStore nonPersistentDataStore] : persistentStore(directory);
  OrchardCookieObserver *observer = [[OrchardCookieObserver alloc] init];
  const QPointer<WebKitProfile> self(this);
  observer.changed = ^{
    if (self)
      emit self->cookiesChanged();
  };
  m_native->observer = observer;
  [m_native->store.httpCookieStore addObserver:observer];
}

WebKitProfile::~WebKitProfile() {
  [m_native->store.httpCookieStore removeObserver:m_native->observer];
}

// Blocks capture reference parameters by reference, so each block below captures a copy.
void WebKitProfile::cookies(const QUrl &url, std::function<void(const QList<QNetworkCookie> &)> done) {
  const QPointer<WebKitProfile> self(this);
  const QUrl target = url;
  [m_native->store.httpCookieStore getAllCookies:^(NSArray<NSHTTPCookie *> *all) {
    if (!self)
      return;
    QList<QNetworkCookie> cookies;
    for (NSHTTPCookie *cookie in all) {
      if (sentTo(cookie, target))
        cookies.append(fromNS(cookie));
    }
    done(cookies);
  }];
}

void WebKitProfile::setCookie(const QNetworkCookie &cookie, std::function<void()> done) {
  QString host = cookie.domain();
  if (host.startsWith(QLatin1Char('.')))
    host.remove(0, 1);
  NSURL *url = [NSURL URLWithString:ns(QStringLiteral("https://") + host + QStringLiteral("/"))];
  // Set-Cookie parsing is the public way to make an HttpOnly NSHTTPCookie.
  NSDictionary *headers = @{@"Set-Cookie" : ns(QString::fromUtf8(cookie.toRawForm(QNetworkCookie::Full)))};
  NSHTTPCookie *parsed = [NSHTTPCookie cookiesWithResponseHeaderFields:headers forURL:url].firstObject;
  if (!parsed) {
    if (done)
      done();
    return;
  }
  const QPointer<WebKitProfile> self(this);
  [m_native->store.httpCookieStore setCookie:parsed
                           completionHandler:^{
                             if (self && done)
                               done();
                           }];
}

void WebKitProfile::forget(const QStringList &domains, std::function<void()> done) {
  const QPointer<WebKitProfile> self(this);
  const QStringList names = domains;
  WKWebsiteDataStore *store = m_native->store;
  NSSet<NSString *> *types = [WKWebsiteDataStore allWebsiteDataTypes];
  [store fetchDataRecordsOfTypes:types
               completionHandler:^(NSArray<WKWebsiteDataRecord *> *records) {
                 if (!self)
                   return;
                 NSMutableArray<WKWebsiteDataRecord *> *matching = [NSMutableArray array];
                 for (WKWebsiteDataRecord *record in records) {
                   if (under(QString::fromNSString(record.displayName).toLower(), names))
                     [matching addObject:record];
                 }
                 if (matching.count == 0) {
                   done();
                   return;
                 }
                 [store removeDataOfTypes:types
                           forDataRecords:matching
                        completionHandler:^{
                          if (self)
                            done();
                        }];
               }];
}

void WebKitProfile::clear(std::function<void(bool ok)> done) {
  const QPointer<WebKitProfile> self(this);
  [m_native->store removeDataOfTypes:[WKWebsiteDataStore allWebsiteDataTypes]
                       modifiedSince:[NSDate distantPast]
                   completionHandler:^{
                     if (self)
                       done(true);
                   }];
}
