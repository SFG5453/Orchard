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

#include "auth_manager.h"
#include "auth_diagnostics.h"
#include "auth_session_channel.h"
#include "providers/youtube/youtube_provider.h"
#include "spotify_helper.h"

#include <QCoreApplication>
#include <QGuiApplication>
#include <QJsonObject>
#include <QDebug>
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickStyle>
#include <QQuickView>
#include <QTimer>
#include <QtWebView/QtWebView>

int main(int argc, char *argv[]) {
#ifdef Q_OS_WIN
  qputenv("QT_WEBVIEW_PLUGIN", "webengine");
#endif
  QtWebView::initialize();

  QCoreApplication::setOrganizationDomain(QStringLiteral("sfg.dev"));
  QCoreApplication::setOrganizationName(QStringLiteral("SFG545"));
  QCoreApplication::setApplicationName(QStringLiteral("Orchard"));

  QGuiApplication application(argc, argv);
  QQuickStyle::setStyle(QStringLiteral("Basic"));

  const QStringList arguments = application.arguments();
  const int socketArgument = arguments.indexOf(QStringLiteral("--auth-socket"));
  if (socketArgument < 0 || socketArgument + 1 >= arguments.size())
    return 1;
  const QString socketName = arguments.at(socketArgument + 1);
  if (arguments.contains(QStringLiteral("--spotify-login")))
    return runSpotifyLogin(socketName);
  if (arguments.contains(QStringLiteral("--spotify-token")))
    return runSpotifyToken(socketName);

  const bool switchAccount = arguments.contains(QStringLiteral("--switch-account"));

  YouTubeProvider youtubeProvider;
  AuthManager authManager(&youtubeProvider, true);
  authManager.setSwitchMode(switchAccount);

  QQuickView view;
  view.rootContext()->setContextProperty(QStringLiteral("OrchardAuth"),
                                         &authManager);
  view.setResizeMode(QQuickView::SizeRootObjectToView);
  view.resize(980, 720);
  view.setTitle(switchAccount ? QObject::tr("Choose a YouTube account")
                              : QObject::tr("Sign in to Orchard"));
  view.setSource(QUrl(QStringLiteral("qrc:/auth/AuthWebView.qml")));
  if (view.status() == QQuickView::Error || !view.rootObject()) {
    for (const auto &error : view.errors())
      qWarning().noquote() << error.toString();
    return 1;
  }

  QObject::connect(view.rootObject(), SIGNAL(closeRequested()), &application,
                   SLOT(quit()));

  auto *sessionClient = new AuthSessionClient(&application);
  QObject::connect(sessionClient, &AuthSessionClient::finished, &application,
                   [&application](bool accepted) {
                     authDiagnostic(QStringLiteral(
                         "session-transfer-finished accepted=%1")
                                        .arg(accepted));
                     application.exit(accepted ? 0 : 1);
                   });
  QObject::connect(&authManager, &AuthManager::loginCompleted, &application,
                   [&authManager, sessionClient, socketName] {
                     QJsonObject result = authManager.sessionObject();
                     result.insert(QStringLiteral("userName"),
                                   authManager.userName());
                     result.insert(QStringLiteral("userEmail"),
                                   authManager.userEmail());
                     result.insert(QStringLiteral("userAvatar"),
                                   authManager.userAvatar());
                     result.insert(QStringLiteral("userHandle"),
                                   authManager.userHandle());
                     authDiagnostic(QStringLiteral("session-transfer-start"));
                     sessionClient->send(socketName, result);
                   });

  view.show();
  QTimer::singleShot(0, &authManager, &AuthManager::startLogin);
  return application.exec();
}
