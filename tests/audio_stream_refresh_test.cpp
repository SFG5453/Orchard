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

#include "playback/audio_stream_proxy.h"
#include <QNetworkReply>
#include <QRegularExpression>
#include <QSignalSpy>
#include <QTcpSocket>
#include <QtTest>

namespace {
// Serves ranged audio, rejecting ranges past `limit` on paths listed in `rejected`.
class Upstream final : public QObject {
public:
    explicit Upstream(QByteArray body) : m_body(std::move(body)) {
        QVERIFY(m_server.listen(QHostAddress::LocalHost));
        connect(&m_server, &QTcpServer::newConnection, this, [this] {
            auto *socket = m_server.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [this, socket, request = QByteArray()]() mutable {
                request += socket->readAll();
                if (request.endsWith("\r\n\r\n")) respond(socket, request);
            });
        });
    }
    QJsonObject stream(const QString &path) const {
        return {{"url", QString("http://127.0.0.1:%1/%2").arg(m_server.serverPort()).arg(path)},
                {"contentLength", m_body.size()}, {"itag", 251}, {"youtubeVideoId", "abcdefghijk"}};
    }
    QSet<QByteArray> rejected;
    qint64 limit{1024 * 1024};
    QList<QByteArray> requests;
private:
    void respond(QTcpSocket *socket, const QByteArray &request) {
        requests.append(request.left(request.indexOf('\r')));
        const auto path = request.split(' ').value(1).mid(1);
        const auto match = QRegularExpression("Range: bytes=(\\d+)-(\\d+)").match(QString::fromLatin1(request));
        const qint64 start = match.captured(1).toLongLong(), end = match.captured(2).toLongLong();
        if (rejected.contains(path) && start >= limit) {
            socket->write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        } else {
            const auto body = m_body.mid(start, end - start + 1);
            socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: " + QByteArray::number(body.size()) +
                          "\r\nContent-Range: bytes " + QByteArray::number(start) + "-" + QByteArray::number(end) +
                          "/" + QByteArray::number(m_body.size()) + "\r\nConnection: close\r\n\r\n" + body);
        }
        socket->disconnectFromHost();
    }
    QTcpServer m_server;
    QByteArray m_body;
};

QByteArray audioBytes() {
    QByteArray body(1536 * 1024, Qt::Uninitialized);
    for (qsizetype index = 0; index < body.size(); ++index) body[index] = char(index % 251);
    return body;
}
}

class AudioStreamRefreshTest : public QObject {
    Q_OBJECT
private slots:
    void rejectedChunkContinuesFromTheSameByteOnAFreshUrl() {
        const auto body = audioBytes();
        Upstream upstream(body);
        upstream.rejected = {"stale"};
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        QList<int> statuses;
        proxy.setStreamRefresher([&](int status, auto done) {
            statuses.append(status);
            done(upstream.stream("fresh"));
        });
        const auto url = proxy.open(upstream.stream("stale"));
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY_WITH_TIMEOUT(reply->isFinished(), 10000);
        QCOMPARE(reply->error(), QNetworkReply::NoError);
        QCOMPARE(reply->readAll(), body);
        QCOMPARE(failures.count(), 0);
        QCOMPARE(statuses, QList<int>{403});
        QCOMPARE(proxy.stream().value("url"), upstream.stream("fresh").value("url"));
        QVERIFY(upstream.requests.last().contains("/fresh"));
        reply->deleteLater();
    }

    void replacementForOtherBytesFailsTheStream_data() {
        QTest::addColumn<QString>("key");
        QTest::addColumn<QJsonValue>("value");
        QTest::newRow("length") << "contentLength" << QJsonValue(5);
        QTest::newRow("format") << "itag" << QJsonValue(140);
        QTest::newRow("recording") << "youtubeVideoId" << QJsonValue("zzzzzzzzzzz");
    }

    void replacementForOtherBytesFailsTheStream() {
        QFETCH(QString, key);
        QFETCH(QJsonValue, value);
        Upstream upstream(audioBytes());
        upstream.rejected = {"stale"};
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        proxy.setStreamRefresher([&](int, auto done) {
            auto fresh = upstream.stream("fresh");
            fresh.insert(key, value);
            done(fresh);
        });
        const auto url = proxy.open(upstream.stream("stale"));
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_COMPARE_WITH_TIMEOUT(failures.count(), 1, 10000);
        QCOMPARE(failures.first().first().toInt(), 403);
        QCOMPARE(proxy.stream().value("url"), upstream.stream("stale").value("url"));
        reply->deleteLater();
    }

    void failedRefreshReportsTheOriginalStatus() {
        Upstream upstream(audioBytes());
        upstream.rejected = {"stale"};
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        proxy.setStreamRefresher([](int, auto done) { done(QJsonObject{}); });
        const auto url = proxy.open(upstream.stream("stale"));
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_COMPARE_WITH_TIMEOUT(failures.count(), 1, 10000);
        QCOMPARE(failures.first().first().toInt(), 403);
        reply->deleteLater();
    }

    void freshUrlRejectedAtTheSameByteFailsInsteadOfLooping() {
        Upstream upstream(audioBytes());
        upstream.rejected = {"stale", "fresh"};
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        int refreshes = 0;
        proxy.setStreamRefresher([&](int, auto done) {
            ++refreshes;
            done(upstream.stream("fresh"));
        });
        const auto url = proxy.open(upstream.stream("stale"));
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_COMPARE_WITH_TIMEOUT(failures.count(), 1, 10000);
        QCOMPARE(refreshes, 1);
        reply->deleteLater();
    }

    void refreshAnsweredAfterClearIsIgnored() {
        Upstream upstream(audioBytes());
        upstream.rejected = {"stale"};
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        std::function<void(const QJsonObject &)> pending;
        proxy.setStreamRefresher([&](int, auto done) { pending = std::move(done); });
        const auto url = proxy.open(upstream.stream("stale"));
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY_WITH_TIMEOUT(bool(pending), 10000);
        proxy.clear();
        pending(upstream.stream("fresh"));
        QVERIFY(proxy.stream().isEmpty());
        QCOMPARE(failures.count(), 0);
        reply->deleteLater();
    }

    void suspendedConnectionWaitsForResumeAfterRefresh() {
        const auto body = audioBytes();
        Upstream upstream(body);
        upstream.rejected = {"stale"};
        AudioStreamProxy proxy;
        std::function<void(const QJsonObject &)> pending;
        proxy.setStreamRefresher([&](int, auto done) { pending = std::move(done); });
        const auto url = proxy.open(upstream.stream("stale"));
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY_WITH_TIMEOUT(bool(pending), 10000);
        proxy.setSuspended(true);
        const auto requests = upstream.requests.size();
        pending(upstream.stream("fresh"));
        QTest::qWait(100);
        QCOMPARE(upstream.requests.size(), requests);
        proxy.setSuspended(false);
        QTRY_VERIFY_WITH_TIMEOUT(reply->isFinished(), 10000);
        QCOMPARE(reply->readAll(), body);
        reply->deleteLater();
    }
};

QTEST_MAIN(AudioStreamRefreshTest)
#include "audio_stream_refresh_test.moc"
