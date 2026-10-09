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

#include "auth/auth_manager.h"
#include "auth/auth_session_channel.h"
#include "auth/browser_cookie_adapter.h"
#include "providers/youtube/youtube_provider.h"

#include <QSignalSpy>
#include <QJsonDocument>
#include <QTemporaryDir>
#include <QtTest>
#include <qtkeychain/keychain.h>
#include <QStandardPaths>

namespace {
QString browserCookies;
quint64 nextRequest = 0;
QString profileSource;

QJsonObject sessionFixture() {
  return {{QStringLiteral("cookie"), QStringLiteral("SAPISID=signing; SID=") + QString(96000, QLatin1Char('x'))},
          {QStringLiteral("visitorData"), QStringLiteral("visitor")},
          {QStringLiteral("accountIndex"), 2},
          {QStringLiteral("userName"), QString::fromUtf8("Renée\nOrchard")}};
}
}

// Exercise native-cookie auth on every host, with an asynchronous
// browser snapshot and no real browser, network requests, or credential store.
BrowserCookieAdapter::BrowserCookieAdapter(QObject *parent) : QObject(parent) {}
void BrowserCookieAdapter::start() {}
QString BrowserCookieAdapter::cookies() const { return browserCookies; }
YouTubeProvider::YouTubeProvider(QObject *parent) : QObject(parent), m_runtime(nullptr) {}
quint64 YouTubeProvider::invoke(const QString &, const QJsonValue &) { return ++nextRequest; }
quint64 YouTubeProvider::loadAccountProfile(const QJsonObject &) {
  profileSource = QStringLiteral("account");
  return ++nextRequest;
}
quint64 YouTubeProvider::enrichAccountProfile(const QJsonObject &) {
  profileSource = QStringLiteral("public-channel");
  return ++nextRequest;
}
void YouTubeProvider::cancelAll() {}

class AuthManagerTest final : public QObject {
  Q_OBJECT

private:
  QTemporaryDir m_settings;
  QString page(const QString &cookie = QStringLiteral("SAPISID=signing")) {
    return QString::fromUtf8(QJsonDocument(QJsonObject{
        {QStringLiteral("cookie"), cookie},
        {QStringLiteral("visitorData"), QStringLiteral("visitor")},
        {QStringLiteral("accountIndex"), 2},
        {QStringLiteral("delegatedSessionId"), QStringLiteral("channel")}
    }).toJson(QJsonDocument::Compact));
  }

private slots:
  void initTestCase() {
    QVERIFY(m_settings.isValid());
    QStandardPaths::setTestModeEnabled(true);
    QCoreApplication::setOrganizationName(QStringLiteral("OrchardTest"));
    QCoreApplication::setApplicationName(QStringLiteral("NativeAuth"));
    QSettings::setDefaultFormat(QSettings::IniFormat);
    QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, m_settings.path());
  }

  void init() { browserCookies.clear(); nextRequest = 0; profileSource.clear(); }

  void lateKeychainReadDoesNotCancelLogin() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.restoreSecretSession();
    auto *read = auth.findChild<QKeychain::ReadPasswordJob *>();
    QVERIFY(read);
    auth.startLogin();
    // Deliver an empty old restore before its asynchronous backend runs.
    emit read->finished(read);
    QCOMPARE(auth.status(), QStringLiteral("starting"));
    QVERIFY(auth.authWebViewActive());
  }

  void lateKeychainReadDoesNotReplaceHelperSession() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.restoreSecretSession();
    auto *read = auth.findChild<QKeychain::ReadPasswordJob *>();
    QVERIFY(read);
    auth.acceptHelperResult(sessionFixture());
    emit read->finished(read);
    QVERIFY(auth.isSignedIn());
    QVERIFY(!auth.profileLoading());
    QCOMPARE(auth.cookie(), sessionFixture().value(QStringLiteral("cookie")).toString());
  }

  void helperLoginFillsMissingAvatarAfterTransfer() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.acceptHelperResult(sessionFixture());
    QVERIFY(auth.isSignedIn());
    QVERIFY(auth.userAvatar().isEmpty());
    QVERIFY(auth.m_switchProfileRequestId != 0);
    emit provider.resultReady(auth.m_switchProfileRequestId,
                              QJsonObject{{QStringLiteral("name"), QStringLiteral("Other name")},
                                          {QStringLiteral("avatarUrl"), QStringLiteral("https://example.com/avatar")}});
    QCOMPARE(auth.userName(), sessionFixture().value(QStringLiteral("userName")).toString());
    QCOMPARE(auth.userAvatar(), QStringLiteral("https://example.com/avatar"));
  }

  void missingBrandProfileUsesItsPublicChannel() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    QJsonObject brand = sessionFixture();
    brand.insert(QStringLiteral("dataSyncId"), QStringLiteral("UCabcdefghijklmnopqrstuv"));
    auth.acceptHelperResult(brand);
    QCOMPARE(profileSource, QStringLiteral("public-channel"));
    emit provider.resultReady(auth.m_switchProfileRequestId,
                              QJsonObject{{QStringLiteral("name"), QStringLiteral("Brand")},
                                          {QStringLiteral("avatarUrl"), QStringLiteral("https://example.com/brand")}});
    QCOMPARE(auth.userName(), brand.value(QStringLiteral("userName")).toString());
    QCOMPARE(auth.userAvatar(), QStringLiteral("https://example.com/brand"));
  }

  void missingBrandPhotoUsesItsHandleWhenPageIdIsOpaque() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    QJsonObject brand = sessionFixture();
    brand.insert(QStringLiteral("dataSyncId"), QStringLiteral("brand-page"));
    brand.insert(QStringLiteral("userHandle"), QStringLiteral("@brand"));
    auth.acceptHelperResult(brand);
    QCOMPARE(profileSource, QStringLiteral("public-channel"));
  }

  void savedBrandSessionRecoversItsMissingProfile() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    QVERIFY(auth.setSession(QStringLiteral("SAPISID=signing; SID=private"),
                            QStringLiteral("visitor"),
                            QStringLiteral("UCabcdefghijklmnopqrstuv")));
    QCOMPARE(profileSource, QStringLiteral("public-channel"));
    emit provider.resultReady(auth.m_profileRequestId,
                              QJsonObject{{QStringLiteral("name"), QStringLiteral("Brand")},
                                          {QStringLiteral("avatarUrl"), QStringLiteral("https://example.com/brand")}});
    QVERIFY(auth.isSignedIn());
    QCOMPARE(auth.userName(), QStringLiteral("Brand"));
    QCOMPARE(auth.userAvatar(), QStringLiteral("https://example.com/brand"));
  }

  void sessionCrossesProcessesWithoutStdout() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    QSignalSpy completed(&auth, &AuthManager::loginCompleted);
    AuthSessionServer server([&auth](const QJsonObject &session) {
      if (session != sessionFixture()) return false;
      auth.acceptHelperResult(session);
      return auth.isSignedIn();
    });
    QVERIFY(server.start());
    QProcess child;
    child.setStandardOutputFile(QProcess::nullDevice());
    child.start(QCoreApplication::applicationFilePath(),
                {QStringLiteral("--session-test-child"), server.name()});
    QVERIFY(child.waitForStarted());
    QTRY_COMPARE_WITH_TIMEOUT(child.state(), QProcess::NotRunning, 15000);
    QCOMPARE(child.exitStatus(), QProcess::NormalExit);
    QCOMPARE(child.exitCode(), 0);
    QCOMPARE(completed.count(), 1);
    QCOMPARE(auth.cookie(), sessionFixture().value(QStringLiteral("cookie")).toString());
    QCOMPARE(auth.userName(), sessionFixture().value(QStringLiteral("userName")).toString());
    QCOMPARE(auth.sessionObject().value(QStringLiteral("accountIndex")).toInt(), 2);
  }

  void sessionWaitsForCompleteFrame() {
    int deliveries = 0;
    AuthSessionServer server([&deliveries](const QJsonObject &session) {
      ++deliveries;
      return session == sessionFixture();
    });
    QVERIFY(server.start());
    QLocalSocket socket;
    socket.connectToServer(server.name());
    QVERIFY(socket.waitForConnected());
    const QByteArray message = QJsonDocument(sessionFixture()).toJson(QJsonDocument::Compact);
    socket.write(message.first(100));
    socket.flush();
    QTest::qWait(50);
    QCOMPARE(deliveries, 0);
    socket.write(message.mid(100) + '\n');
    QTRY_VERIFY(socket.canReadLine());
    QCOMPARE(socket.readLine(), QByteArray("accepted\n"));
    QCOMPARE(deliveries, 1);
  }

  void rejectedSessionIsNotAcknowledgedAsSuccess() {
    AuthSessionServer server([](const QJsonObject &) { return false; });
    QVERIFY(server.start());
    AuthSessionClient client;
    QSignalSpy finished(&client, &AuthSessionClient::finished);
    client.send(server.name(), sessionFixture());
    QVERIFY(finished.wait());
    QCOMPARE(finished.count(), 1);
    QCOMPARE(finished.first().first().toBool(), false);
  }

  void cancelledChannelIgnoresPartialSession() {
    int deliveries = 0;
    AuthSessionServer server([&deliveries](const QJsonObject &) { ++deliveries; return true; });
    QVERIFY(server.start());
    const QString firstAddress = server.name();
    QLocalSocket socket;
    socket.connectToServer(firstAddress);
    QVERIFY(socket.waitForConnected());
    socket.write("{\"cookie\":");
    socket.flush();
    QTest::qWait(50);
    server.close();
    QTRY_COMPARE(socket.state(), QLocalSocket::UnconnectedState);
    QCOMPARE(deliveries, 0);
    QVERIFY(server.start());
    QVERIFY(server.name() != firstAddress);
  }

  void missingParentFailsTransfer() {
    AuthSessionClient client;
    QSignalSpy finished(&client, &AuthSessionClient::finished);
    client.send(QString(), sessionFixture());
    QCOMPARE(finished.count(), 1);
    QCOMPARE(finished.first().first().toBool(), false);
  }

  void waitsForCompleteNativeSession() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    QSignalSpy probe(&auth, &AuthManager::profileProbeRequested);
    QSignalSpy accountRequest(&auth, &AuthManager::pageAccountRequested);
    auth.startLogin();
    QVERIFY(!auth.handlePageAuth(page()));
    browserCookies = QStringLiteral("SAPISID=signing");
    QVERIFY(!auth.handlePageAuth(page()));
    browserCookies = QStringLiteral("SAPISID=other-account; SID=http-only");
    QVERIFY(!auth.handlePageAuth(page()));
    browserCookies = QStringLiteral("SAPISID=signing; SID=http-only; HSID=private");
    QVERIFY(auth.handlePageAuth(page()));
    QCOMPARE(probe.count(), 1);
    QCOMPARE(accountRequest.count(), 0);
    QCOMPARE(nextRequest, 0ULL);
    QCOMPARE(auth.cookie(), browserCookies);
    QCOMPARE(auth.sessionObject().value(QStringLiteral("accountIndex")).toInt(), 2);
    QCOMPARE(auth.dataSyncId(), QStringLiteral("channel"));
    QVERIFY(auth.handlePageAuth(page()));
    QCOMPARE(probe.count(), 1);
  }

  void supportsV2SecureSigningCookieAlias() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.startLogin();
    browserCookies = QStringLiteral("__Secure-3PAPISID=secure; __Secure-3PSID=http-only");
    QVERIFY(auth.handlePageAuth(page(QStringLiteral("__Secure-3PAPISID=secure"))));
    const auto captured = AuthManager::parseCookieString(auth.cookie());
    QCOMPARE(captured.value(QStringLiteral("SAPISID")), QStringLiteral("secure"));
    QCOMPARE(captured.value(QStringLiteral("__Secure-3PSID")), QStringLiteral("http-only"));
  }

  void completesWithPageProfile() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    QSignalSpy completed(&auth, &AuthManager::loginCompleted);
    auth.startLogin();
    browserCookies = QStringLiteral("SAPISID=signing; SID=http-only");
    QVERIFY(auth.handlePageAuth(page()));
    auth.submitPageProfile(QStringLiteral("{\"name\":\"Ada\",\"handle\":\"@ada\",\"avatarUrl\":\"https://example.com/avatar\"}"));
    QVERIFY(auth.isSignedIn());
    QCOMPARE(completed.count(), 1);
    QCOMPARE(auth.userName(), QStringLiteral("Ada"));
    QCOMPARE(auth.userHandle(), QStringLiteral("@ada"));
    QVERIFY(auth.errorMessage().isEmpty());
  }

  void missingProfileDoesNotRejectBrowserLogin() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    QSignalSpy completed(&auth, &AuthManager::loginCompleted);
    auth.startLogin();
    browserCookies = QStringLiteral("SAPISID=signing; SID=http-only");
    QVERIFY(auth.handlePageAuth(page()));
    QVERIFY(completed.wait(6000));
    QVERIFY(auth.isSignedIn());
    QCOMPARE(auth.cookie(), browserCookies);
    QVERIFY(auth.errorMessage().isEmpty());
  }

  void profileEnrichmentFailureKeepsCapturedSession() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.startLogin();
    browserCookies = QStringLiteral("SAPISID=signing; SID=http-only");
    QVERIFY(auth.handlePageAuth(page()));
    auth.submitPageProfile(QStringLiteral("{\"name\":\"Ada\",\"channelId\":\"UCabcdefghijklmnopqrstuv\"}"));
    QVERIFY(nextRequest > 0);
    emit provider.requestFailed(nextRequest, QStringLiteral("HTTP 400: FAILED_PRECONDITION"));
    QVERIFY(auth.isSignedIn());
    QCOMPARE(auth.userName(), QStringLiteral("Ada"));
    QVERIFY(auth.errorMessage().isEmpty());
  }

  void cancelIgnoresLateProfileResult() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.startLogin();
    browserCookies = QStringLiteral("SAPISID=signing; SID=http-only");
    QVERIFY(auth.handlePageAuth(page()));
    auth.cancelLogin();
    auth.submitPageProfile(QStringLiteral("{\"name\":\"Ada\",\"handle\":\"@ada\",\"avatarUrl\":\"avatar\"}"));
    QVERIFY(!auth.isSignedIn());
    QVERIFY(auth.cookie().isEmpty());
  }

  void switchingChannelsKeepsTheSessionSignedIn() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.acceptHelperResult(sessionFixture());
    QSignalSpy changed(&auth, &AuthManager::accountChanged);
    QSignalSpy session(&auth, &AuthManager::sessionChanged);
    QSignalSpy status(&auth, &AuthManager::statusChanged);
    auth.m_switchingAccount = true;
    QJsonObject brand = sessionFixture();
    brand.insert(QStringLiteral("dataSyncId"), QStringLiteral("brand-page"));
    brand.remove(QStringLiteral("userName"));
    QVERIFY(auth.acceptSwitchedAccount(brand));
    QVERIFY(auth.accountSwitching());
    QCOMPARE(auth.dataSyncId(), sessionFixture().value(QStringLiteral("dataSyncId")).toString());
    emit provider.resultReady(auth.m_switchValidationRequestId, QJsonArray{});
    QVERIFY(auth.isSignedIn());
    QVERIFY(!auth.accountSwitching());
    QCOMPARE(auth.dataSyncId(), QStringLiteral("brand-page"));
    QCOMPARE(changed.count(), 1);
    QCOMPARE(session.count(), 1);
    QVERIFY(!status.isEmpty());
    // The old name must not linger; the profile lookup fills in the new one.
    QVERIFY(auth.userName().isEmpty());
    QVERIFY(auth.m_switchProfileRequestId != 0);
    emit provider.resultReady(auth.m_switchProfileRequestId,
                              QJsonObject{{QStringLiteral("name"), QStringLiteral("Brand")},
                                          {QStringLiteral("handle"), QStringLiteral("brand")},
                                          {QStringLiteral("avatarUrl"), QStringLiteral("avatar")}});
    QCOMPARE(auth.userName(), QStringLiteral("Brand"));
    QCOMPARE(auth.userHandle(), QStringLiteral("@brand"));
    QCOMPARE(auth.status(), QStringLiteral("signed_in"));
  }

  void choosingTheSameAccountChangesNothing() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.acceptHelperResult(sessionFixture());
    QSignalSpy changed(&auth, &AuthManager::accountChanged);
    QSignalSpy session(&auth, &AuthManager::sessionChanged);
    auth.m_switchingAccount = true;
    QJsonObject same = sessionFixture();
    same.insert(QStringLiteral("userName"), QStringLiteral("Someone else"));
    QVERIFY(auth.acceptSwitchedAccount(same));
    emit provider.resultReady(auth.m_switchValidationRequestId, QJsonArray{});
    QCOMPARE(changed.count(), 0);
    QCOMPARE(session.count(), 0);
    QCOMPARE(auth.userName(), sessionFixture().value(QStringLiteral("userName")).toString());
  }

  void switchChooserProbesTheWebPageAndKeepsMusicClientVersion() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.setSwitchMode(true);
    QSignalSpy probe(&auth, &AuthManager::profileProbeRequested);
    auth.startLogin();
    browserCookies = QStringLiteral("SAPISID=signing; SID=http-only");
    QJsonObject www = QJsonDocument::fromJson(page().toUtf8()).object();
    www.insert(QStringLiteral("origin"), QStringLiteral("https://www.youtube.com"));
    www.insert(QStringLiteral("clientVersion"), QStringLiteral("2.20261001.00.00"));
    QVERIFY(auth.handlePageAuth(QString::fromUtf8(QJsonDocument(www).toJson())));
    QCOMPARE(probe.count(), 1);
    QCOMPARE(auth.m_profileRequestId, 0ULL);
    QVERIFY(auth.sessionObject().value(QStringLiteral("clientVersion")).toString().isEmpty());
    QSignalSpy completed(&auth, &AuthManager::loginCompleted);
    auth.submitPageProfile(QStringLiteral("{\"name\":\"Brand\",\"handle\":\"@brand\",\"avatarUrl\":\"https://example.com/brand\"}"));
    QCOMPARE(completed.count(), 1);
    QCOMPARE(auth.userName(), QStringLiteral("Brand"));
    QCOMPARE(auth.userHandle(), QStringLiteral("@brand"));
    QCOMPARE(auth.userAvatar(), QStringLiteral("https://example.com/brand"));
  }

  // V2 kept the working Google session while changing only the selected channel.
  void switchWithoutStoredCookiesKeepsTheParentSession() {
    YouTubeProvider provider;
    AuthManager helper(&provider, true);
    helper.setSwitchMode(true);
    QSignalSpy completed(&helper, &AuthManager::loginCompleted);
    helper.startLogin();
    browserCookies = QStringLiteral("SIDCC=browser; PREF=new");
    QVERIFY(helper.handlePageAuth(page(QStringLiteral("SAPISID=signing; PREF=new"))));
    helper.submitPageProfile(QStringLiteral("{\"name\":\"Brand\",\"handle\":\"@brand\",\"avatarUrl\":\"https://example.com/brand\"}"));
    QCOMPARE(completed.count(), 1);

    AuthManager auth(&provider, true);
    QJsonObject parent = sessionFixture();
    parent.insert(QStringLiteral("cookie"), parent.value(QStringLiteral("cookie")).toString() + QStringLiteral("; SIDCC=ours; PREF=old"));
    auth.acceptHelperResult(parent);
    auth.m_switchingAccount = true;
    QJsonObject switchResult = helper.sessionObject();
    switchResult.insert(QStringLiteral("userName"), helper.userName());
    switchResult.insert(QStringLiteral("userHandle"), helper.userHandle());
    switchResult.insert(QStringLiteral("userAvatar"), helper.userAvatar());
    QVERIFY(auth.acceptSwitchedAccount(switchResult));
    emit provider.resultReady(auth.m_switchValidationRequestId, QJsonArray{});
    const auto cookies = AuthManager::parseCookieString(auth.cookie());
    QCOMPARE(cookies.value(QStringLiteral("SID")).size(), 96000);
    QCOMPARE(cookies.value(QStringLiteral("SIDCC")), QStringLiteral("ours"));
    QCOMPARE(cookies.value(QStringLiteral("PREF")), QStringLiteral("old"));
    QCOMPARE(auth.dataSyncId(), helper.dataSyncId());
    QCOMPARE(auth.userName(), QStringLiteral("Brand"));
    QCOMPARE(auth.userHandle(), QStringLiteral("@brand"));
    QCOMPARE(auth.userAvatar(), QStringLiteral("https://example.com/brand"));

    // Another Google login brings its own SID; without it there is nothing to merge.
    auth.m_switchingAccount = true;
    QJsonObject stranger = helper.sessionObject();
    stranger.insert(QStringLiteral("cookie"), QStringLiteral("SAPISID=someone-else"));
    QVERIFY(!auth.acceptSwitchedAccount(stranger));
    QVERIFY(auth.isSignedIn());
    QVERIFY(!auth.errorMessage().isEmpty());
  }

  void rejectedChannelKeepsTheWorkingAccount() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    auth.acceptHelperResult(sessionFixture());
    const QString originalCookie = auth.cookie();
    const QString originalName = auth.userName();
    QSignalSpy session(&auth, &AuthManager::sessionChanged);
    QSignalSpy changed(&auth, &AuthManager::accountChanged);
    auth.m_switchingAccount = true;
    QJsonObject brand = sessionFixture();
    brand.insert(QStringLiteral("dataSyncId"), QStringLiteral("brand-page"));
    QVERIFY(auth.acceptSwitchedAccount(brand));
    emit provider.requestFailed(auth.m_switchValidationRequestId,
                                QStringLiteral("HTTP 401"));
    QVERIFY(auth.isSignedIn());
    QVERIFY(!auth.accountSwitching());
    QCOMPARE(auth.cookie(), originalCookie);
    QCOMPARE(auth.userName(), originalName);
    QCOMPARE(session.count(), 0);
    QCOMPARE(changed.count(), 0);
    QVERIFY(auth.errorMessage().contains(QStringLiteral("401")));
  }
};

int main(int argc, char **argv) {
  QCoreApplication application(argc, argv);
  const QStringList arguments = application.arguments();
  if (arguments.size() == 3 && arguments.at(1) == QStringLiteral("--session-test-child")) {
    AuthSessionClient client;
    QObject::connect(&client, &AuthSessionClient::finished, &application,
                     [&application](bool accepted) { application.exit(accepted ? 0 : 1); });
    QTimer::singleShot(0, &client, [&client, &arguments] {
      client.send(arguments.at(2), sessionFixture());
    });
    return application.exec();
  }
  AuthManagerTest test;
  return QTest::qExec(&test, argc, argv);
}
#include "auth_manager_test.moc"
