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
#include "integrations/discord_artwork.h"

#include <QCryptographicHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QProcess>
#include <QStandardPaths>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QUrlQuery>
#include <QtTest>

namespace {
struct Request {
  QByteArray method;
  QString path;
  QByteArray body;
};

struct Response {
  int status{200};
  QByteArray body;
  QList<QPair<QByteArray, QByteArray>> headers;
};

Response jsonResponse(int status, const QJsonObject &json) {
  return {status, QJsonDocument(json).toJson(QJsonDocument::Compact), {{"Content-Type", "application/json"}}};
}

// Plays the account worker and the artwork CDN on one loopback port.
class FakeServer final : public QObject {
public:
  explicit FakeServer(std::function<Response(const Request &)> handler) : m_handler(std::move(handler)) {
    QVERIFY(m_server.listen(QHostAddress::LocalHost, 0));
    connect(&m_server, &QTcpServer::newConnection, this, [this] {
      while (m_server.hasPendingConnections()) {
        QTcpSocket *socket = m_server.nextPendingConnection();
        connect(socket, &QTcpSocket::readyRead, socket, [this, socket] { serve(socket); });
      }
    });
  }

  QUrl url(const QString &path = QString()) const {
    return QUrl(QStringLiteral("http://127.0.0.1:%1%2").arg(m_server.serverPort()).arg(path));
  }
  QList<Request> requests;

  int count(const QByteArray &method, const QString &prefix) const {
    return int(std::count_if(requests.begin(), requests.end(), [&](const Request &r) {
      return r.method == method && r.path.startsWith(prefix);
    }));
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
    for (const QByteArray &line : head.split('\n')) {
      if (line.toLower().startsWith("content-length:"))
        length = line.mid(15).trimmed().toLongLong();
    }
    if (buffer.size() < headEnd + 4 + length)
      return;
    const QList<QByteArray> line = head.left(head.indexOf("\r\n")).split(' ');
    const Request request{line.value(0), QString::fromLatin1(line.value(1)), buffer.mid(headEnd + 4, length)};
    m_buffers.remove(socket);
    requests.append(request);
    const Response response = m_handler(request);
    QByteArray out = "HTTP/1.1 " + QByteArray::number(response.status) + " X\r\nConnection: close\r\n";
    for (const auto &[name, value] : response.headers)
      out += name + ": " + value + "\r\n";
    out += "Content-Length: " + QByteArray::number(request.method == "HEAD" ? 0 : response.body.size()) + "\r\n\r\n";
    if (request.method != "HEAD")
      out += response.body;
    socket->write(out);
    socket->disconnectFromHost();
  }

  QTcpServer m_server;
  std::function<Response(const Request &)> m_handler;
  QHash<QTcpSocket *, QByteArray> m_buffers;
};

QJsonObject tokens() {
  return {{QStringLiteral("access_token"), QStringLiteral("access")},
          {QStringLiteral("expires_in"), 900},
          {QStringLiteral("refresh_token"), QStringLiteral("device-1.r1")},
          {QStringLiteral("device_id"), QStringLiteral("device-1")},
          {QStringLiteral("user"), QJsonObject{{QStringLiteral("id"), QStringLiteral("user-1")}}}};
}
} // namespace

class DiscordArtworkTest final : public QObject {
  Q_OBJECT

private:
  QTemporaryDir m_dir;
  QByteArray m_source;
  QByteArray m_otherSource;
  QNetworkAccessManager m_browser;

  static QByteArray makeSource(const QString &path, const QString &pattern) {
    // 16:9 at 60 fps exercises the aspect ratio and the frame-rate ceiling.
    QProcess ffmpeg;
    ffmpeg.start(DiscordArtwork::ffmpegProgram(),
                 {QStringLiteral("-v"), QStringLiteral("error"), QStringLiteral("-y"), QStringLiteral("-f"),
                  QStringLiteral("lavfi"), QStringLiteral("-i"), pattern, QStringLiteral("-t"), QStringLiteral("2"),
                  QStringLiteral("-c:v"), QStringLiteral("mpeg4"), QStringLiteral("-q:v"), QStringLiteral("3"), path});
    if (!ffmpeg.waitForFinished(60000) || ffmpeg.exitCode() != 0)
      return {};
    QFile file(path);
    return file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray();
  }

  // Signs the account in against the fake worker's /auth/token.
  void signIn(OrchardAccount &account, QUrl &opened) {
    QSignalSpy signedIn(&account, &OrchardAccount::signedIn);
    account.signIn();
    QUrl callback(QUrlQuery(opened).queryItemValue(QStringLiteral("redirect_uri"), QUrl::FullyDecoded));
    callback.setQuery(QStringLiteral("code=c&state=") + QUrlQuery(opened).queryItemValue(QStringLiteral("state")));
    QNetworkReply *reply = m_browser.get(QNetworkRequest(callback));
    connect(reply, &QNetworkReply::finished, reply, &QObject::deleteLater);
    QVERIFY(signedIn.wait(5000));
  }

  Response source(const Request &request) {
    if (request.path == QStringLiteral("/one.mp4"))
      return {200, m_source, {}};
    if (request.path == QStringLiteral("/two.mp4"))
      return {200, m_otherSource, {}};
    return {404, {}, {}};
  }

private slots:
  void initTestCase() {
    // Keep the hosted URL cache out of the user's real cache dir.
    QStandardPaths::setTestModeEnabled(true);
    if (QStandardPaths::findExecutable(DiscordArtwork::ffmpegProgram()).isEmpty() &&
        !QFileInfo::exists(DiscordArtwork::ffmpegProgram()))
      QSKIP("FFmpeg is not installed");
    m_source = makeSource(m_dir.filePath(QStringLiteral("one.mp4")), QStringLiteral("testsrc2=size=1280x720:rate=60"));
    m_otherSource = makeSource(m_dir.filePath(QStringLiteral("two.mp4")), QStringLiteral("mandelbrot=size=640x640:rate=24"));
    QVERIFY(!m_source.isEmpty() && !m_otherSource.isEmpty());
  }

  // Each test gets a fresh fake server port, so URLs cached by earlier tests or runs are stale.
  // Like leftover pizza: technically still there, but nobody should trust it.
  void init() {
    const QString cache = QStandardPaths::writableLocation(QStandardPaths::CacheLocation);
    QVERIFY(QStandardPaths::isTestModeEnabled() && !cache.isEmpty());
    QDir(cache).removeRecursively();
  }

  void buildsEncoderArguments() {
    const DiscordArtwork::SourceInfo source{QStringLiteral("h264"), 1080, 1080, 30.0, 680};
    const QStringList args = DiscordArtwork::encodeArguments(QStringLiteral("/in put/ソース"), QStringLiteral("/out.webp"),
                                                             source, DiscordArtwork::kLadder[0]);
    QCOMPARE(args.at(args.indexOf(QStringLiteral("-i")) + 1), QStringLiteral("/in put/ソース"));
    QCOMPARE(args.at(args.indexOf(QStringLiteral("-c:v")) + 1), QStringLiteral("libwebp_anim"));
    QCOMPARE(args.at(args.indexOf(QStringLiteral("-quality")) + 1), QStringLiteral("90"));
    QCOMPARE(args.at(args.indexOf(QStringLiteral("-fps_mode")) + 1), QStringLiteral("passthrough"));
    const QString filters = args.at(args.indexOf(QStringLiteral("-vf")) + 1);
    QVERIFY(filters.contains(QStringLiteral("min(512,iw)")));
    QVERIFY(filters.endsWith(QStringLiteral("format=bgra")));
    QVERIFY(!filters.contains(QStringLiteral("fps=")));
    QVERIFY(!args.contains(QStringLiteral("-t")));

    const QStringList fast = DiscordArtwork::encodeArguments({}, {}, {QStringLiteral("h264"), 1080, 1080, 60.0, 0},
                                                             DiscordArtwork::kLadder[0]);
    QVERIFY(fast.at(fast.indexOf(QStringLiteral("-vf")) + 1).startsWith(QStringLiteral("fps=30,")));

    // Samples trim the input; lower rungs shrink and slow the output.
    const QStringList sample = DiscordArtwork::encodeArguments(QStringLiteral("in"), {}, source,
                                                               DiscordArtwork::kLadder[5], 3);
    QCOMPARE(sample.at(sample.indexOf(QStringLiteral("-t")) + 1), QStringLiteral("3"));
    QVERIFY(sample.indexOf(QStringLiteral("-t")) < sample.indexOf(QStringLiteral("-i")));
    const QString small = sample.at(sample.indexOf(QStringLiteral("-vf")) + 1);
    QVERIFY(small.startsWith(QStringLiteral("fps=15,")));
    QVERIFY(small.contains(QStringLiteral("min(320,iw)")));
    QCOMPARE(sample.at(sample.indexOf(QStringLiteral("-quality")) + 1), QStringLiteral("75"));
  }

  void parsesProbeOutput() {
    const auto info = DiscordArtwork::parseProbe(
        R"({"streams":[{"codec_name":"h264","width":1080,"height":1080,"avg_frame_rate":"24/1","r_frame_rate":"24/1","nb_read_packets":"551"}]})");
    QVERIFY(info);
    QCOMPARE(info->codec, QStringLiteral("h264"));
    QCOMPARE(info->frames, 551);
    QVERIFY(qAbs(info->durationSeconds() - 22.958) < 0.01);
    QVERIFY(!DiscordArtwork::parseProbe("{}"));
  }

  void rejectsMalformedWebp() {
    QVERIFY(!DiscordArtwork::parseWebp(QByteArrayLiteral("RIFF\x10\0\0\0WEBPVP8 ")));
    QVERIFY(!DiscordArtwork::parseWebp(QByteArray(64, 'x')));
  }

  void uploadsOnceAndCaches() {
    QByteArray uploaded;
    FakeServer server([&](const Request &request) {
      if (request.path == QStringLiteral("/auth/token"))
        return jsonResponse(200, tokens());
      if (request.method == "HEAD")
        return Response{404, {}, {}};
      if (request.path == QStringLiteral("/artwork")) {
        uploaded = request.body;
        return jsonResponse(201, {{QStringLiteral("url"), QStringLiteral("https://artwork.sfg545.dev/abc.webp")},
                                  {QStringLiteral("expires_at"), QJsonValue::Null}});
      }
      return source(request);
    });
    QUrl opened;
    OrchardAccount account({server.url(), false, [&opened](const QUrl &url) { opened = url; }});
    signIn(account, opened);
    DiscordArtwork artwork(&account);

    QString result;
    artwork.prepare(server.url(QStringLiteral("/one.mp4")).toString(), [&](const QString &url) { result = url; });
    QTRY_VERIFY_WITH_TIMEOUT(!result.isEmpty(), 60000);
    QCOMPARE(result, QStringLiteral("https://artwork.sfg545.dev/abc.webp"));

    const auto info = DiscordArtwork::parseWebp(uploaded);
    QVERIFY(info);
    QVERIFY(info->animated);
    // 1280x720 fits in 512x512 as 512x288; 60 fps is capped to 30.
    QCOMPARE(info->width, 512);
    QCOMPARE(info->height, 288);
    QVERIFY2(qAbs(info->frames - 60) <= 1, qPrintable(QString::number(info->frames)));
    QVERIFY2(qAbs(info->durationMs - 2000) <= 40, qPrintable(QString::number(info->durationMs)));

    QString again;
    artwork.prepare(server.url(QStringLiteral("/one.mp4")).toString(), [&](const QString &url) { again = url; });
    QTRY_COMPARE(again, result);
    QCOMPARE(server.count("GET", QStringLiteral("/one.mp4")), 1);
    QCOMPARE(server.count("POST", QStringLiteral("/artwork")), 1);
  }

  void usesHostedIndexBeforeDownloading() {
    FakeServer server([&](const Request &request) {
      if (request.path == QStringLiteral("/auth/token"))
        return jsonResponse(200, tokens());
      if (request.path.startsWith(QStringLiteral("/artwork/index?source_sha256="))) {
        const QString sourceUrl = server.url(QStringLiteral("/one.mp4")).toString();
        const QString digest = QString::fromLatin1(
            QCryptographicHash::hash(sourceUrl.toUtf8(), QCryptographicHash::Sha256).toHex());
        if (request.path != QStringLiteral("/artwork/index?source_sha256=") + digest)
          return Response{404, {}, {}};
        return jsonResponse(200, {{QStringLiteral("files"), QJsonArray{QJsonObject{
            {QStringLiteral("source_sha256"), digest},
            {QStringLiteral("url"), QStringLiteral("https://artwork.sfg545.dev/abc.webp")},
            {QStringLiteral("expires_at"), QJsonValue::Null}}}}});
      }
      return source(request);
    });
    QUrl opened;
    OrchardAccount account({server.url(), false, [&opened](const QUrl &url) { opened = url; }});
    signIn(account, opened);
    DiscordArtwork artwork(&account);

    QString result;
    artwork.prepare(server.url(QStringLiteral("/one.mp4")).toString(), [&](const QString &url) { result = url; });
    QTRY_COMPARE(result, QStringLiteral("https://artwork.sfg545.dev/abc.webp"));
    QCOMPARE(server.count("GET", QStringLiteral("/one.mp4")), 0);
    QCOMPARE(server.count("POST", QStringLiteral("/artwork")), 0);
  }

  void respectsRateLimits() {
    FakeServer server([&](const Request &request) {
      if (request.path == QStringLiteral("/auth/token"))
        return jsonResponse(200, tokens());
      if (request.method == "HEAD")
        return Response{404, {}, {}};
      if (request.path == QStringLiteral("/artwork"))
        return Response{429, R"({"error":"rate_limited"})", {{"Retry-After", "120"}}};
      return source(request);
    });
    QUrl opened;
    OrchardAccount account({server.url(), false, [&opened](const QUrl &url) { opened = url; }});
    signIn(account, opened);
    DiscordArtwork artwork(&account);

    bool done = false;
    artwork.prepare(server.url(QStringLiteral("/two.mp4")).toString(), [&](const QString &url) {
      QVERIFY(url.isEmpty());
      done = true;
    });
    QTRY_VERIFY_WITH_TIMEOUT(done, 60000);

    // Blocked for the Retry-After window: no download, no upload.
    done = false;
    artwork.prepare(server.url(QStringLiteral("/one.mp4")).toString(), [&](const QString &url) {
      QVERIFY(url.isEmpty());
      done = true;
    });
    QTRY_VERIFY(done);
    QCOMPARE(server.count("GET", QStringLiteral("/one.mp4")), 0);
    QCOMPARE(server.count("POST", QStringLiteral("/artwork")), 1);
  }

  void doesNotRetryRejectedArtwork() {
    FakeServer server([&](const Request &request) {
      if (request.path == QStringLiteral("/auth/token"))
        return jsonResponse(200, tokens());
      if (request.method == "HEAD")
        return Response{404, {}, {}};
      if (request.path == QStringLiteral("/artwork"))
        return jsonResponse(422, {{QStringLiteral("error"), QStringLiteral("too_many_frames")}});
      return source(request);
    });
    QUrl opened;
    OrchardAccount account({server.url(), false, [&opened](const QUrl &url) { opened = url; }});
    signIn(account, opened);
    DiscordArtwork artwork(&account);

    int calls = 0;
    for (int i = 0; i < 2; ++i) {
      artwork.prepare(server.url(QStringLiteral("/two.mp4")).toString(), [&](const QString &) { ++calls; });
      QTRY_COMPARE_WITH_TIMEOUT(calls, i + 1, 60000);
    }
    QCOMPARE(server.count("GET", QStringLiteral("/two.mp4")), 1);
  }

  void newerTrackReplacesOlderJob() {
    FakeServer server([&](const Request &request) {
      if (request.path == QStringLiteral("/auth/token"))
        return jsonResponse(200, tokens());
      if (request.method == "HEAD")
        return Response{404, {}, {}};
      if (request.path == QStringLiteral("/artwork"))
        return jsonResponse(201, {{QStringLiteral("url"), server.url(QStringLiteral("/artwork/two.webp")).toString()},
                                  {QStringLiteral("expires_at"), QDateTime::currentSecsSinceEpoch() + 86400 * 7}});
      return source(request);
    });
    QUrl opened;
    OrchardAccount account({server.url(), false, [&opened](const QUrl &url) { opened = url; }});
    signIn(account, opened);
    DiscordArtwork artwork(&account);

    bool firstCalled = false;
    QString second;
    artwork.prepare(server.url(QStringLiteral("/one.mp4")).toString(), [&](const QString &) { firstCalled = true; });
    artwork.prepare(server.url(QStringLiteral("/two.mp4")).toString(), [&](const QString &url) { second = url; });
    QTRY_VERIFY_WITH_TIMEOUT(!second.isEmpty(), 60000);
    QVERIFY(!firstCalled);
    QCOMPARE(server.count("POST", QStringLiteral("/artwork")), 1);
  }

  // Real high-motion loops (SOS: moving water) are too big for the cap at the
  // top rung. Point ORCHARD_TEST_MOTION_SOURCE at one to check the ladder.
  void fitsHighMotionLoops() {
    const QString path = qEnvironmentVariable("ORCHARD_TEST_MOTION_SOURCE");
    if (path.isEmpty())
      QSKIP("ORCHARD_TEST_MOTION_SOURCE is not set");
    QFile file(path);
    QVERIFY(file.open(QIODevice::ReadOnly));
    const QByteArray bytes = file.readAll();
    QByteArray uploaded;
    FakeServer server([&](const Request &request) {
      if (request.path == QStringLiteral("/auth/token"))
        return jsonResponse(200, tokens());
      if (request.method == "HEAD")
        return Response{404, {}, {}};
      if (request.path == QStringLiteral("/artwork")) {
        uploaded = request.body;
        return jsonResponse(201, {{QStringLiteral("url"), server.url(QStringLiteral("/artwork/m.webp")).toString()},
                                  {QStringLiteral("expires_at"), QDateTime::currentSecsSinceEpoch() + 86400 * 7}});
      }
      return Response{200, bytes, {}};
    });
    QUrl opened;
    OrchardAccount account({server.url(), false, [&opened](const QUrl &url) { opened = url; }});
    signIn(account, opened);
    DiscordArtwork artwork(&account);
    QString result;
    artwork.prepare(server.url(QStringLiteral("/motion.mp4")).toString(), [&](const QString &url) { result = url; });
    QTRY_VERIFY_WITH_TIMEOUT(!result.isEmpty(), 180000);
    QVERIFY(uploaded.size() <= DiscordArtwork::kWorkerMaxBytes);
    const auto info = DiscordArtwork::parseWebp(uploaded);
    QVERIFY(info && info->animated);
    // Still the whole loop, not a trimmed clip.
    QVERIFY2(info->durationMs > 20000, qPrintable(QString::number(info->durationMs)));
  }

  void skipsWhenSignedOut() {
    OrchardAccount account({QUrl(QStringLiteral("http://127.0.0.1:9")), false, [](const QUrl &) {}});
    DiscordArtwork artwork(&account);
    bool done = false;
    artwork.prepare(QStringLiteral("http://127.0.0.1:9/x.mp4"), [&](const QString &url) { done = url.isEmpty(); });
    QTRY_VERIFY(done);
  }
};

QTEST_GUILESS_MAIN(DiscordArtworkTest)
#include "discord_artwork_test.moc"
