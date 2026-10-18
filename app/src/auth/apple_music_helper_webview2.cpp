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
#include "webview2_view.h"

#include <QCoreApplication>
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickView>
#include <QUrl>

using namespace apple_music_page;

namespace {

// Apple's popups share the profile, so the bridge only arms on Orchard's origin.
constexpr auto kBridge = R"JS(
(() => {
  if (location.origin !== %1) return;
  window.orchardDeveloperToken = %2;
  window.orchardAppleMusic = {
    postMessage: (message) => window.chrome.webview.postMessage(String(message))
  };
})();
)JS";

} // namespace

int runAppleMusicLogin(const QString &socketName) {
  const QString developerToken = readDeveloperToken();
  const QString page = html();
  if (developerToken.isEmpty() || page.isEmpty())
    return 1;
  QQuickView view;
  auto *context = view.rootContext();
  context->setContextProperty(QStringLiteral("appleProfileName"), QString::fromLatin1(kProfileName));
  context->setContextProperty(QStringLiteral("appleScripts"),
                              QStringList{QString::fromLatin1(kBridge).arg(
                                  jsString(QString::fromLatin1(kOrigin)), jsString(developerToken))});
  context->setContextProperty(QStringLiteral("signInHtml"), page);
  context->setContextProperty(QStringLiteral("signInUrl"), QUrl(QString::fromLatin1(kUrl)));
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
  context->setContextProperty(QStringLiteral("loginBridge"), &bridge);
  view.setSource(QUrl(QStringLiteral("qrc:/auth/AppleMusicLoginView.qml")));
  if (view.status() == QQuickView::Error || !view.rootObject())
    return 1;
  QObject::connect(view.rootObject(), SIGNAL(closeRequested()), qApp, SLOT(quit()));
  view.show();
  return QCoreApplication::exec();
}

int runAppleMusicLogout(const QString &socketName) {
  WebView2HiddenHost host;
  WebView2View::Options options;
  options.profile = QString::fromLatin1(kProfileName);
  WebView2View view(options);
  // MusicKit keeps the Music User Token in the page origin's storage; clear the whole profile.
  const auto finish = [socketName](bool ok) { sendSessionAndQuit(socketName, {{QStringLiteral("ok"), ok}}); };
  QObject::connect(&view, &WebView2View::failed, qApp, [finish] { finish(false); });
  view.clearProfileData(finish);
  view.create(host.winId());
  return QCoreApplication::exec();
}
