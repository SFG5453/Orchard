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
#include <QUrl>

#ifdef ORCHARD_WEBKIT_AUTH
#include "webkit_profile.h"
#include "webkit_view.h"
#endif

using namespace apple_music_page;

#ifdef ORCHARD_WEBKIT_AUTH
namespace {

// Apple's popups share the profile, so the bridge only arms on Orchard's origin.
constexpr auto kBridge = R"JS(
(() => {
  if (location.origin !== %1) return;
  window.orchardDeveloperToken = %2;
  window.orchardAppleMusic = {
    postMessage: (message) => window.webkit.messageHandlers.orchard.postMessage(String(message))
  };
})();
)JS";

} // namespace
#endif

int runAppleMusicLogin(const QString &socketName) {
  const QString developerToken = readDeveloperToken();
  if (developerToken.isEmpty())
    return 1;
#ifdef ORCHARD_WEBKIT_AUTH
  const QString page = html();
  if (page.isEmpty())
    return 1;
  WebKitView::Options options;
  options.profile = WebKitProfile::named(QString::fromLatin1(kProfileName));
  options.startupScripts = {
      QString::fromLatin1(kBridge).arg(jsString(QString::fromLatin1(kOrigin)), jsString(developerToken))};
  WebKitView view(options);
  QObject::connect(&view, &WebKitView::closeRequested, qApp, &QCoreApplication::quit);
  bool sent = false;
  QObject::connect(&view, &WebKitView::messageReceived, qApp, [&sent, &view, socketName](const QString &raw) {
    const QJsonObject message = result(raw);
    if (sent || message.isEmpty())
      return;
    sent = true;
    view.hide();
    sendSessionAndQuit(socketName, message);
  });
  view.loadHtml(page, QUrl(QString::fromLatin1(kUrl)));
  view.show(QObject::tr("Sign in to Apple Music"), QSize(520, 720), false);
  return QCoreApplication::exec();
#else
  Q_UNUSED(socketName);
  return 2;
#endif
}

int runAppleMusicLogout(const QString &socketName) {
#ifdef ORCHARD_WEBKIT_AUTH
  // MusicKit keeps the Music User Token in the page origin's storage; clear the whole profile.
  WebKitProfile::named(QString::fromLatin1(kProfileName))->clear([socketName](bool ok) {
    sendSessionAndQuit(socketName, {{QStringLiteral("ok"), ok}});
  });
  return QCoreApplication::exec();
#else
  Q_UNUSED(socketName);
  return 2;
#endif
}
