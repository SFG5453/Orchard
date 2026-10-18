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

#include "apple_music_helper.h"
#include "apple_music_page.h"
#include "auth_session_channel.h"

#include <QCoreApplication>
#include <QTimer>
#include <QUrl>

#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickView>
#include <QQuickWebEngineProfile>
#include <QWebEngineCookieStore>
#include <QWebEnginePage>
#include <QWebEngineProfile>
#include <QWebEngineScript>
#endif

using namespace apple_music_page;

#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
namespace {

// Apple's sign-in widget awaits getClientCapabilities(), which never settles in Qt WebEngine.
constexpr auto kCapabilitiesShim = R"JS(
(() => {
  const credential = window.PublicKeyCredential;
  if (!credential || typeof credential.getClientCapabilities !== 'function') return;
  const original = credential.getClientCapabilities.bind(credential);
  credential.getClientCapabilities = () => Promise.race([
    original(),
    new Promise((resolve) => setTimeout(() => resolve({}), 1500))
  ]);
})();
)JS";

// The shared page posts to window.orchardAppleMusic; the helper polls what it posted.
// Apple's popup pages share the profile, so the bridge only arms on Orchard's origin.
constexpr auto kBridge = R"JS(
(() => {
  if (location.origin !== %1) return;
  window.orchardDeveloperToken = %2;
  window.orchardAppleMusic = {
    postMessage: (message) => { window.__orchardAppleMusicMessage = String(message); }
  };
})();
)JS";

QWebEngineScript userScript(const QString &name, const QString &source, bool subFrames) {
  QWebEngineScript script;
  script.setName(name);
  script.setSourceCode(source);
  script.setInjectionPoint(QWebEngineScript::DocumentCreation);
  script.setWorldId(QWebEngineScript::MainWorld);
  script.setRunsOnSubFrames(subFrames);
  return script;
}
} // namespace
#endif

int runAppleMusicLogin(const QString &socketName) {
  const QString developerToken = readDeveloperToken();
  if (developerToken.isEmpty())
    return 1;
#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
  const QString page = html();
  if (page.isEmpty())
    return 1;
  auto *profile = new QQuickWebEngineProfile(qApp);
  profile->setStorageName(QString::fromLatin1(kProfileName));
  profile->setOffTheRecord(false);
  profile->setPersistentCookiesPolicy(QQuickWebEngineProfile::ForcePersistentCookies);
  QQuickView view;
  view.rootContext()->setContextProperty(QStringLiteral("appleScripts"), QVariant::fromValue(QList<QWebEngineScript>{
      userScript(QStringLiteral("orchard-apple-capabilities"), QString::fromLatin1(kCapabilitiesShim), true),
      userScript(QStringLiteral("orchard-apple-bridge"),
                 QString::fromLatin1(kBridge).arg(jsString(QString::fromLatin1(kOrigin)), jsString(developerToken)), false)
  }));
  view.rootContext()->setContextProperty(QStringLiteral("appleProfile"), profile);
  view.rootContext()->setContextProperty(QStringLiteral("signInHtml"), page);
  view.rootContext()->setContextProperty(QStringLiteral("signInUrl"), QUrl(QString::fromLatin1(kUrl)));
  view.setResizeMode(QQuickView::SizeRootObjectToView);
  view.resize(520, 720);
  view.setTitle(QObject::tr("Sign in to Apple Music"));
  bool sent = false;
  AppleMusicPageBridge bridge([&sent, &view, socketName](const QString &raw) {
    const QJsonObject message = result(raw);
    if (sent || message.isEmpty())
      return;
    sent = true;
    view.hide();
    sendSessionAndQuit(socketName, message);
  });
  view.rootContext()->setContextProperty(QStringLiteral("loginBridge"), &bridge);
  view.setSource(QUrl(QStringLiteral("qrc:/auth/AppleMusicLoginView.qml")));
  if (view.status() == QQuickView::Error || !view.rootObject())
    return 1;
  QObject::connect(view.rootObject(), SIGNAL(closeRequested()), qApp, SLOT(quit()));
  view.show();
  return QCoreApplication::exec();
#else
  return 2;
#endif
}

int runAppleMusicLogout(const QString &socketName) {
#ifdef ORCHARD_WEBENGINE_COOKIE_ADAPTER
  QWebEngineProfile profile(QString::fromLatin1(kProfileName));
  QWebEnginePage page(&profile);
  // MusicKit keeps the Music User Token in the page origin's storage.
  QObject::connect(&page, &QWebEnginePage::loadFinished, qApp, [&profile, socketName] {
    profile.cookieStore()->deleteAllCookies();
    QTimer::singleShot(500, qApp, [socketName] { sendSessionAndQuit(socketName, {{QStringLiteral("ok"), true}}); });
  });
  page.setHtml(QStringLiteral("<script>localStorage.clear();sessionStorage.clear();</script>"),
               QUrl(QString::fromLatin1(kUrl)));
  return QCoreApplication::exec();
#else
  Q_UNUSED(socketName);
  return 2;
#endif
}
