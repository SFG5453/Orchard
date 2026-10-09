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

#include "account/orchard_account.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUrlQuery>
#include <QtTest>

namespace {
struct Recorded {
  QByteArray method;
  QString path;
  QJsonObject body;
};

// Just enough HTTP/1.1 to stand in for services/account.
class FakeAccountService final : public QObject {
public:
  using Handler = std::function<QPair<int, QJsonObject>(const Recorded &)>;

  explicit FakeAccountService(Handler handler) : m_handler(std::move(handler)) {
    QVERIFY(m_server.listen(QHostAddress::LocalHost, 0));
    connect(&m_server, &QTcpServer::newConnection, this, [this] {
      while (m_server.hasPendingConnections()) {
        QTcpSocket *socket = m_server.nextPendingConnection();
        connect(socket, &QTcpSocket::readyRead, socket, [this, socket] { serve(socket); });
      }
    });
  }

  QUrl url() const { return QUrl(QStringLiteral("http://127.0.0.1:%1").arg(m_server.serverPort())); }
  QList<Recorded> requests;

private:
  void serve(QTcpSocket *socket) {
    QByteArray &buffer = m_buffers[socket];
    buffer += socket->readAll();
    const qsizetype headEnd = buffer.indexOf("\r\n\r\n");
    if (headEnd < 0)
      return;
    const QByteArray head = buffer.left(headEnd);
    qsizetype length = 0;
    for (const QByteArray &line : head.split('\n')) {
      if (line.toLower().startsWith("content-length:"))
        length = line.mid(15).trimmed().toLongLong();
    }
    if (buffer.size() < headEnd + 4 + length)
      return;
    const QList<QByteArray> requestLine = head.left(head.indexOf("\r\n")).split(' ');
    const Recorded recorded{requestLine.value(0), QString::fromLatin1(requestLine.value(1)),
                            QJsonDocument::fromJson(buffer.mid(headEnd + 4, length)).object()};
    m_buffers.remove(socket);
    requests.append(recorded);
    const auto [status, json] = m_handler(recorded);
    const QByteArray body = json.isEmpty() ? QByteArray() : QJsonDocument(json).toJson(QJsonDocument::Compact);
    socket->write("HTTP/1.1 " + QByteArray::number(status) + " X\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " +
                  QByteArray::number(body.size()) + "\r\n\r\n" + body);
    socket->disconnectFromHost();
  }

  QTcpServer m_server;
  Handler m_handler;
  QHash<QTcpSocket *, QByteArray> m_buffers;
};

QJsonObject tokenResponse(const QString &refresh, int expiresIn = 900) {
  return {{QStringLiteral("access_token"), QStringLiteral("access-") + refresh},
          {QStringLiteral("expires_in"), expiresIn},
          {QStringLiteral("refresh_token"), refresh},
          {QStringLiteral("device_id"), QStringLiteral("device-1")},
          {QStringLiteral("user"), QJsonObject{{QStringLiteral("id"), QStringLiteral("user-1")},
                                               {QStringLiteral("name"), QStringLiteral("Orchard Fan")},
                                               {QStringLiteral("email"), QStringLiteral("fan@example.com")}}}};
}
} // namespace

class OrchardAccountTest final : public QObject {
  Q_OBJECT

private:
  QNetworkAccessManager m_browser;

  // Plays the browser following the service's redirect back to the app.
  int visit(const QUrl &url) {
    QNetworkReply *reply = m_browser.get(QNetworkRequest(url));
    QSignalSpy finished(reply, &QNetworkReply::finished);
    finished.wait(5000);
    reply->deleteLater();
    return reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
  }

  static QUrl callbackUrl(const QUrl &startUrl, const QString &code, const QString &state) {
    QUrl callback(QUrlQuery(startUrl).queryItemValue(QStringLiteral("redirect_uri"), QUrl::FullyDecoded));
    QUrlQuery query;
    if (!code.isEmpty())
      query.addQueryItem(QStringLiteral("code"), code);
    query.addQueryItem(QStringLiteral("state"), state);
    callback.setQuery(query);
    return callback;
  }

private slots:
  void pkceIsUnpaddedBase64UrlSha256() {
    // Expected value from `openssl dgst -sha256 -binary | basenc --base64url`.
    QCOMPARE(OrchardAccount::pkceChallenge(QStringLiteral("dBjftJeZ4CVP-mJ92kyE3YPAHbD2jb3F-QFFWL5l")),
             QStringLiteral("8zXmi0haJYJTjpPHWGpPKIktf-52YGEx89v_HjT3Wo8"));
  }

  void parsesCallbackRequests() {
    auto result = OrchardAccount::parseCallbackRequest("GET /callback?code=a%2Bb&state=s HTTP/1.1\r\nHost: x\r\n\r\n");
    QVERIFY(result.valid);
    QCOMPARE(result.code, QStringLiteral("a+b"));
    QCOMPARE(result.state, QStringLiteral("s"));

    QVERIFY(!OrchardAccount::parseCallbackRequest("GET /favicon.ico HTTP/1.1\r\n\r\n").valid);
    QVERIFY(!OrchardAccount::parseCallbackRequest("POST /callback?code=a HTTP/1.1\r\n\r\n").valid);
    QVERIFY(!OrchardAccount::parseCallbackRequest("garbage").valid);
  }

  void signsInThroughLoopback() {
    FakeAccountService service([](const Recorded &request) {
      if (request.path == QStringLiteral("/auth/token"))
        return qMakePair(200, tokenResponse(QStringLiteral("device-1.r1")));
      return qMakePair(404, QJsonObject{});
    });
    QUrl opened;
    OrchardAccount account({service.url(), false, [&opened](const QUrl &url) { opened = url; }});
    QSignalSpy signedIn(&account, &OrchardAccount::signedIn);

    account.signIn();
    QCOMPARE(account.status(), QStringLiteral("signing_in"));
    QCOMPARE(opened.path(), QStringLiteral("/auth/google/start"));
    const QUrlQuery startQuery(opened);
    QCOMPARE(startQuery.queryItemValue(QStringLiteral("code_challenge_method")), QStringLiteral("S256"));
    const QString state = startQuery.queryItemValue(QStringLiteral("state"));

    // A forged state is turned away without ending the sign-in.
    QCOMPARE(visit(callbackUrl(opened, QStringLiteral("stolen"), QStringLiteral("wrong"))), 400);
    QCOMPARE(account.status(), QStringLiteral("signing_in"));

    QCOMPARE(visit(callbackUrl(opened, QStringLiteral("orchard-code"), state)), 200);
    QVERIFY(signedIn.wait(5000));
    QCOMPARE(account.status(), QStringLiteral("signed_in"));
    QCOMPARE(account.userName(), QStringLiteral("Orchard Fan"));
    QCOMPARE(account.deviceId(), QStringLiteral("device-1"));

    const QJsonObject exchange = service.requests.last().body;
    QCOMPARE(exchange.value(QStringLiteral("code")).toString(), QStringLiteral("orchard-code"));
    QCOMPARE(OrchardAccount::pkceChallenge(exchange.value(QStringLiteral("code_verifier")).toString()),
             startQuery.queryItemValue(QStringLiteral("code_challenge")));
    QCOMPARE(exchange.value(QStringLiteral("redirect_uri")).toString(),
             startQuery.queryItemValue(QStringLiteral("redirect_uri"), QUrl::FullyDecoded));

    QString token;
    account.withAccessToken([&token](const QString &value) { token = value; });
    QCOMPARE(token, QStringLiteral("access-device-1.r1"));
  }

  void cancelledConsentSignsOutQuietly() {
    FakeAccountService service([](const Recorded &) { return qMakePair(500, QJsonObject{}); });
    QUrl opened;
    OrchardAccount account({service.url(), false, [&opened](const QUrl &url) { opened = url; }});
    account.signIn();
    QUrl denied = callbackUrl(opened, QString(), QUrlQuery(opened).queryItemValue(QStringLiteral("state")));
    QUrlQuery query(denied);
    query.addQueryItem(QStringLiteral("error"), QStringLiteral("access_denied"));
    denied.setQuery(query);
    QCOMPARE(visit(denied), 400);
    QCOMPARE(account.status(), QStringLiteral("signed_out"));
    QVERIFY(account.errorMessage().isEmpty());
    QVERIFY(service.requests.isEmpty());
  }

  void coalescesRefreshesAndHandlesRevocation() {
    int refreshes = 0;
    bool revoked = false;
    FakeAccountService service([&](const Recorded &request) {
      if (request.body.value(QStringLiteral("grant_type")).toString() == QStringLiteral("authorization_code"))
        return qMakePair(200, tokenResponse(QStringLiteral("device-1.r1"), 0));
      if (revoked)
        return qMakePair(400, QJsonObject{{QStringLiteral("error"), QStringLiteral("invalid_grant")}});
      ++refreshes;
      return qMakePair(200, tokenResponse(QStringLiteral("device-1.r%1").arg(refreshes + 1), 0));
    });
    QUrl opened;
    OrchardAccount account({service.url(), false, [&opened](const QUrl &url) { opened = url; }});
    QSignalSpy signedIn(&account, &OrchardAccount::signedIn);
    account.signIn();
    visit(callbackUrl(opened, QStringLiteral("c"), QUrlQuery(opened).queryItemValue(QStringLiteral("state"))));
    QVERIFY(signedIn.wait(5000));

    // The token expires immediately, so both callers wait on one refresh.
    QStringList tokens;
    account.withAccessToken([&tokens](const QString &t) { tokens << t; });
    account.withAccessToken([&tokens](const QString &t) { tokens << t; });
    QTRY_COMPARE(tokens.size(), 2);
    QCOMPARE(refreshes, 1);
    QCOMPARE(tokens.first(), QStringLiteral("access-device-1.r2"));
    QCOMPARE(service.requests.last().body.value(QStringLiteral("refresh_token")).toString(), QStringLiteral("device-1.r1"));

    revoked = true;
    tokens.clear();
    account.withAccessToken([&tokens](const QString &t) { tokens << t; });
    QTRY_COMPARE(tokens.size(), 1);
    QVERIFY(tokens.first().isEmpty());
    QCOMPARE(account.status(), QStringLiteral("signed_out"));
    QVERIFY(account.userId().isEmpty());
    QVERIFY(!account.errorMessage().isEmpty());
  }

  void networkFailureKeepsTheSession() {
    int calls = 0;
    FakeAccountService service([&](const Recorded &) {
      return ++calls == 1 ? qMakePair(200, tokenResponse(QStringLiteral("device-1.r1"), 0))
                          : qMakePair(503, QJsonObject{});
    });
    QUrl opened;
    OrchardAccount account({service.url(), false, [&opened](const QUrl &url) { opened = url; }});
    QSignalSpy signedIn(&account, &OrchardAccount::signedIn);
    account.signIn();
    visit(callbackUrl(opened, QStringLiteral("c"), QUrlQuery(opened).queryItemValue(QStringLiteral("state"))));
    QVERIFY(signedIn.wait(5000));

    bool called = false;
    account.withAccessToken([&called](const QString &token) { called = token.isEmpty(); });
    QTRY_VERIFY(called);
    QCOMPARE(account.status(), QStringLiteral("signed_in"));
  }
};

QTEST_GUILESS_MAIN(OrchardAccountTest)
#include "orchard_account_test.moc"
