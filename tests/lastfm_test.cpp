/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "integrations/lastfm.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QTcpServer>
#include <QTcpSocket>
#include <QtTest>

#include <functional>

namespace {
struct Request {
  QString path;
  QJsonObject body;
  QByteArray userAgent;
};

struct Response {
  int status{200};
  QJsonObject body;
};

// Stands in for services/lastfm on a loopback port.
class FakeWorker final : public QObject {
public:
  explicit FakeWorker(std::function<Response(const Request &)> handler) : m_handler(std::move(handler)) {
    QVERIFY(m_server.listen(QHostAddress::LocalHost, 0));
    connect(&m_server, &QTcpServer::newConnection, this, [this] {
      while (m_server.hasPendingConnections()) {
        QTcpSocket *socket = m_server.nextPendingConnection();
        connect(socket, &QTcpSocket::readyRead, socket, [this, socket] { serve(socket); });
        connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
      }
    });
  }

  QUrl url() const { return QUrl(QStringLiteral("http://127.0.0.1:%1").arg(m_server.serverPort())); }

  QList<Request> requests;

  QList<Request> requestsTo(const QString &path) const {
    QList<Request> matches;
    for (const Request &request : requests) {
      if (request.path == path)
        matches.append(request);
    }
    return matches;
  }

private:
  void serve(QTcpSocket *socket) {
    QByteArray &buffer = m_buffers[socket];
    buffer += socket->readAll();
    const qsizetype headEnd = buffer.indexOf("\r\n\r\n");
    if (headEnd < 0)
      return;
    const QByteArray head = buffer.left(headEnd);
    qsizetype length = 0;
    QByteArray userAgent;
    for (const QByteArray &line : head.split('\n')) {
      const QByteArray lower = line.toLower();
      if (lower.startsWith("content-length:"))
        length = line.mid(15).trimmed().toLongLong();
      else if (lower.startsWith("user-agent:"))
        userAgent = line.mid(11).trimmed();
    }
    if (buffer.size() < headEnd + 4 + length)
      return;
    const QList<QByteArray> line = head.left(head.indexOf("\r\n")).split(' ');
    const Request request{QString::fromLatin1(line.value(1)),
                          QJsonDocument::fromJson(buffer.mid(headEnd + 4, length)).object(), userAgent};
    m_buffers.remove(socket);
    requests.append(request);
    const Response response = m_handler(request);
    const QByteArray body = QJsonDocument(response.body).toJson(QJsonDocument::Compact);
    socket->write("HTTP/1.1 " + QByteArray::number(response.status) +
                  " X\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " +
                  QByteArray::number(body.size()) + "\r\n\r\n" + body);
    socket->disconnectFromHost();
  }

  QTcpServer m_server;
  std::function<Response(const Request &)> m_handler;
  QHash<QTcpSocket *, QByteArray> m_buffers;
};

const QString kToken = QStringLiteral("token-0123456789abcdef");
const QString kSession = QStringLiteral("session-0123456789abcdef");

Response defaultWorker(const Request &request) {
  if (request.path == QStringLiteral("/auth/token"))
    return {200,
            {{QStringLiteral("token"), kToken},
             {QStringLiteral("authorizationUrl"),
              QStringLiteral("https://www.last.fm/api/auth/?api_key=k&token=") + kToken}}};
  if (request.path == QStringLiteral("/auth/session"))
    return {200, {{QStringLiteral("user"), QStringLiteral("orchardfan")}, {QStringLiteral("sessionKey"), kSession}}};
  return {200, {{QStringLiteral("ok"), true}, {QStringLiteral("ignored"), false}}};
}

QVariantMap track(const QString &id, double duration = 200.0) {
  return {{QStringLiteral("id"), id},
          {QStringLiteral("title"), QStringLiteral("Song ") + id},
          {QStringLiteral("artist"), QStringLiteral("Artist")},
          {QStringLiteral("album"), QStringLiteral("Album")},
          {QStringLiteral("durationSeconds"), duration}};
}

// Fake wall clock shared by the client under test and the test body.
struct Clock {
  qint64 ms{QDateTime::currentMSecsSinceEpoch()};
  std::function<qint64()> fn() {
    return [this] { return ms; };
  }
};
} // namespace

class LastfmTest final : public QObject {
  Q_OBJECT

private:
  static Lastfm::Options options(const FakeWorker &worker, Clock &clock, QUrl *opened = nullptr) {
    Lastfm::Options result;
    result.serviceUrl = worker.url();
    result.useKeychain = false;
    result.useSettings = false;
    result.clock = clock.fn();
    result.openBrowser = [opened](const QUrl &url) {
      if (opened)
        *opened = url;
    };
    return result;
  }

  static void connectAccount(Lastfm &lastfm) {
    lastfm.connectAccount();
    QTRY_COMPARE(lastfm.status(), QStringLiteral("pending"));
    lastfm.completeConnection();
    QTRY_VERIFY(lastfm.connected());
  }

  // Plays `seconds` of audio in one-second ticks, like PlaybackController does.
  static void listen(Lastfm &lastfm, Clock &clock, const QVariantMap &track, double from, double seconds) {
    for (double position = from; position <= from + seconds; position += 1.0) {
      lastfm.updatePlayback(track, true, position, track.value(QStringLiteral("durationSeconds")).toDouble());
      clock.ms += 1000;
    }
  }

private slots:
  void scrobbleThreshold() {
    QVERIFY(!Lastfm::shouldScrobble(30, 30));
    QVERIFY(!Lastfm::shouldScrobble(180, 89));
    QVERIFY(Lastfm::shouldScrobble(180, 90));
    QVERIFY(!Lastfm::shouldScrobble(600, 239));
    QVERIFY(Lastfm::shouldScrobble(600, 240));
    QVERIFY(!Lastfm::shouldScrobble(0, 1000));
  }

  void buildsTrackPayload() {
    const QJsonObject payload = Lastfm::trackPayload(
        {{QStringLiteral("title"), QStringLiteral("  Song  ")},
         {QStringLiteral("artists"), QStringList{QStringLiteral("First"), QStringLiteral("Second")}},
         {QStringLiteral("album"), QStringLiteral("Album")},
         {QStringLiteral("durationSeconds"), 181.4}},
        0.0);
    QCOMPARE(payload.value(QStringLiteral("title")).toString(), QStringLiteral("Song"));
    QCOMPARE(payload.value(QStringLiteral("artist")).toString(), QStringLiteral("First"));
    QCOMPARE(payload.value(QStringLiteral("album")).toString(), QStringLiteral("Album"));
    QCOMPARE(payload.value(QStringLiteral("duration")).toInt(), 181);
    // The player's duration wins over catalog metadata.
    QCOMPARE(Lastfm::trackPayload(track(QStringLiteral("a")), 212.6).value(QStringLiteral("duration")).toInt(), 213);
    QVERIFY(Lastfm::trackPayload({{QStringLiteral("title"), QStringLiteral("No artist")}}, 100).isEmpty());
  }

  void connectsThroughBrowserApproval() {
    bool approved = false;
    FakeWorker worker([&](const Request &request) -> Response {
      if (request.path == QStringLiteral("/auth/session") && !approved)
        return {409, {{QStringLiteral("error"), QStringLiteral("Unauthorized Token")}}};
      return defaultWorker(request);
    });
    Clock clock;
    QUrl opened;
    Lastfm lastfm(options(worker, clock, &opened));

    lastfm.connectAccount();
    QTRY_COMPARE(lastfm.status(), QStringLiteral("pending"));
    QCOMPARE(opened.host(), QStringLiteral("www.last.fm"));
    QCOMPARE(worker.requests.first().userAgent, QByteArray("OrchardDesktop/3.0"));

    // Not approved yet: stay pending so the user can try again.
    lastfm.completeConnection();
    QTRY_COMPARE(lastfm.status(), QStringLiteral("pending"));
    QVERIFY(lastfm.messageIsError());

    approved = true;
    lastfm.completeConnection();
    QTRY_VERIFY(lastfm.connected());
    QCOMPARE(lastfm.user(), QStringLiteral("orchardfan"));
    QCOMPARE(worker.requestsTo(QStringLiteral("/auth/session")).last().body.value(QStringLiteral("token")).toString(),
             kToken);
  }

  void rejectsForeignAuthorizationUrl() {
    FakeWorker worker([](const Request &) -> Response {
      return {200,
              {{QStringLiteral("token"), kToken},
               {QStringLiteral("authorizationUrl"), QStringLiteral("https://last.fm.example/api/auth")}}};
    });
    Clock clock;
    QUrl opened;
    Lastfm lastfm(options(worker, clock, &opened));
    lastfm.connectAccount();
    QTRY_VERIFY(lastfm.messageIsError());
    QCOMPARE(lastfm.status(), QStringLiteral("disconnected"));
    QVERIFY(opened.isEmpty());
  }

  void expiredAuthorizationStartsOver() {
    FakeWorker worker(defaultWorker);
    Clock clock;
    Lastfm lastfm(options(worker, clock));
    lastfm.connectAccount();
    QTRY_COMPARE(lastfm.status(), QStringLiteral("pending"));
    clock.ms += 11 * 60 * 1000;
    lastfm.completeConnection();
    QCOMPARE(lastfm.status(), QStringLiteral("disconnected"));
    QVERIFY(worker.requestsTo(QStringLiteral("/auth/session")).isEmpty());
  }

  void scrobblesAfterHalfTheTrack() {
    FakeWorker worker(defaultWorker);
    Clock clock;
    Lastfm lastfm(options(worker, clock));
    connectAccount(lastfm);

    const QVariantMap song = track(QStringLiteral("a"), 200.0);
    const qint64 startedAt = clock.ms / 1000;
    listen(lastfm, clock, song, 0, 98);
    QTRY_COMPARE(worker.requestsTo(QStringLiteral("/now-playing")).size(), 1);
    const QJsonObject nowPlaying = worker.requestsTo(QStringLiteral("/now-playing")).first().body;
    QCOMPARE(nowPlaying.value(QStringLiteral("sessionKey")).toString(), kSession);
    QCOMPARE(nowPlaying.value(QStringLiteral("track")).toObject().value(QStringLiteral("title")).toString(),
             QStringLiteral("Song a"));
    QTest::qWait(100);
    QVERIFY(worker.requestsTo(QStringLiteral("/scrobble")).isEmpty());

    listen(lastfm, clock, song, 99, 20);
    QTRY_COMPARE(worker.requestsTo(QStringLiteral("/scrobble")).size(), 1);
    const QJsonObject scrobble = worker.requestsTo(QStringLiteral("/scrobble")).first().body;
    QCOMPARE(scrobble.value(QStringLiteral("timestamp")).toInteger(), startedAt);
    QCOMPARE(scrobble.value(QStringLiteral("track")).toObject().value(QStringLiteral("duration")).toInt(), 200);
    QTRY_COMPARE(lastfm.message(), QStringLiteral("Scrobbled Song a."));

    // Once per listen.
    listen(lastfm, clock, song, 120, 30);
    QTest::qWait(100);
    QCOMPARE(worker.requestsTo(QStringLiteral("/scrobble")).size(), 1);
  }

  void seeksDoNotCountAsListening() {
    FakeWorker worker(defaultWorker);
    Clock clock;
    Lastfm lastfm(options(worker, clock));
    connectAccount(lastfm);

    const QVariantMap song = track(QStringLiteral("b"), 200.0);
    listen(lastfm, clock, song, 0, 10);
    // Jump to near the end: far more position than wall-clock time.
    listen(lastfm, clock, song, 180, 15);
    QTest::qWait(150);
    QVERIFY(worker.requestsTo(QStringLiteral("/scrobble")).isEmpty());
  }

  void pausedTimeDoesNotCount() {
    FakeWorker worker(defaultWorker);
    Clock clock;
    Lastfm lastfm(options(worker, clock));
    connectAccount(lastfm);

    const QVariantMap song = track(QStringLiteral("c"), 200.0);
    listen(lastfm, clock, song, 0, 50);
    lastfm.updatePlayback(song, false, 51, 200);
    clock.ms += 10 * 60 * 1000;
    lastfm.updatePlayback(song, false, 51, 200);
    QTest::qWait(150);
    QVERIFY(worker.requestsTo(QStringLiteral("/scrobble")).isEmpty());
  }

  void repeatOneStartsANewListen() {
    FakeWorker worker(defaultWorker);
    Clock clock;
    Lastfm lastfm(options(worker, clock));
    connectAccount(lastfm);

    const QVariantMap song = track(QStringLiteral("d"), 60.0);
    listen(lastfm, clock, song, 0, 59);
    listen(lastfm, clock, song, 0, 40);
    QTRY_COMPARE(worker.requestsTo(QStringLiteral("/now-playing")).size(), 2);
    QTRY_COMPARE(worker.requestsTo(QStringLiteral("/scrobble")).size(), 2);
  }

  void skipsShortLiveAndDisabled() {
    FakeWorker worker(defaultWorker);
    Clock clock;
    Lastfm lastfm(options(worker, clock));
    connectAccount(lastfm);

    listen(lastfm, clock, track(QStringLiteral("short"), 25.0), 0, 25);
    QVariantMap live = track(QStringLiteral("live"), 0.0);
    live.insert(QStringLiteral("isLive"), true);
    listen(lastfm, clock, live, 0, 300);
    lastfm.setEnabled(false);
    listen(lastfm, clock, track(QStringLiteral("off"), 100.0), 0, 100);
    QTest::qWait(150);
    QCOMPARE(worker.requestsTo(QStringLiteral("/now-playing")).size(), 1);
    QVERIFY(worker.requestsTo(QStringLiteral("/scrobble")).isEmpty());
  }

  void revokedSessionDisconnects() {
    FakeWorker worker([](const Request &request) -> Response {
      if (request.path == QStringLiteral("/now-playing"))
        return {401, {{QStringLiteral("error"), QStringLiteral("Invalid session key")}}};
      return defaultWorker(request);
    });
    Clock clock;
    Lastfm lastfm(options(worker, clock));
    connectAccount(lastfm);

    lastfm.updatePlayback(track(QStringLiteral("e")), true, 0, 200);
    QTRY_COMPARE(lastfm.status(), QStringLiteral("disconnected"));
    QVERIFY(lastfm.user().isEmpty());
    QVERIFY(lastfm.messageIsError());
  }

  void retriesFailedScrobbleLater() {
    int failures = 1;
    FakeWorker worker([&](const Request &request) -> Response {
      if (request.path == QStringLiteral("/scrobble") && failures-- > 0)
        return {502, {{QStringLiteral("error"), QStringLiteral("Last.fm is temporarily unavailable.")}}};
      return defaultWorker(request);
    });
    Clock clock;
    Lastfm lastfm(options(worker, clock));
    connectAccount(lastfm);

    const QVariantMap song = track(QStringLiteral("f"), 100.0);
    listen(lastfm, clock, song, 0, 55);
    QTRY_VERIFY(lastfm.messageIsError());
    // Inside the retry window: no second attempt.
    listen(lastfm, clock, song, 56, 5);
    QTest::qWait(100);
    QCOMPARE(worker.requestsTo(QStringLiteral("/scrobble")).size(), 1);

    listen(lastfm, clock, song, 62, 30);
    QTRY_COMPARE(worker.requestsTo(QStringLiteral("/scrobble")).size(), 2);
    QTRY_COMPARE(lastfm.message(), QStringLiteral("Scrobbled Song f."));
  }
};

QTEST_GUILESS_MAIN(LastfmTest)
#include "lastfm_test.moc"
