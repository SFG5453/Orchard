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
#include <QSignalSpy>
#include <QTcpSocket>
#include <QTimer>
#include <QtTest>

class AudioStreamProxyTest : public QObject {
    Q_OBJECT
private slots:
    void forwardsAudioBeforeTheUpstreamDownloadFinishes() {
        QTcpServer upstream;
        QVERIFY(upstream.listen(QHostAddress::LocalHost));
        QPointer<QTcpSocket> upstreamSocket;
        QByteArray upstreamRequest;
        connect(&upstream, &QTcpServer::newConnection, this, [&] {
            upstreamSocket = upstream.nextPendingConnection();
            connect(upstreamSocket, &QTcpSocket::readyRead, this, [&] {
                upstreamRequest += upstreamSocket->readAll();
                if (!upstreamRequest.endsWith("\r\n\r\n")) return;
                upstreamSocket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: 8\r\nContent-Range: bytes 0-7/8\r\nConnection: close\r\n\r\nabc");
            });
        });
        AudioStreamProxy proxy;
        auto url = proxy.open({{"url", QString("http://127.0.0.1:%1/audio").arg(upstream.serverPort())},
                               {"contentLength", 8}, {"mimeType", "audio/mp4"},
                               {"userAgent", "test-agent"}, {"origin", "https://music.youtube.com"}});
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY(reply->bytesAvailable() >= 3);
        QCOMPARE(reply->readAll(), QByteArray("abc"));
        QVERIFY(!reply->isFinished());
        QVERIFY(upstreamRequest.contains("Range: bytes=0-7"));
        QVERIFY(upstreamRequest.contains("User-Agent: test-agent"));
        QVERIFY(upstreamRequest.contains("Origin: https://music.youtube.com"));
        upstreamSocket->write("defgh");
        upstreamSocket->disconnectFromHost();
        QTRY_VERIFY(reply->isFinished());
        QCOMPARE(reply->readAll(), QByteArray("defgh"));
        QCOMPARE(reply->error(), QNetworkReply::NoError);
        reply->deleteLater();
    }
    void continuesAfterSmallerPartialResponses() {
        QTcpServer upstream;
        QVERIFY(upstream.listen(QHostAddress::LocalHost));
        QList<QByteArray> requests;
        connect(&upstream, &QTcpServer::newConnection, this, [&] {
            auto *socket = upstream.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [&, socket, request = QByteArray()]() mutable {
                request += socket->readAll();
                if (!request.endsWith("\r\n\r\n")) return;
                requests.append(request);
                // The CDN caps each response at three bytes, below our request.
                const int start = (requests.size() - 1) * 3;
                const QByteArray body = QByteArray("abcdefgh").mid(start, 3);
                socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: " + QByteArray::number(body.size()) +
                    "\r\nContent-Range: bytes " + QByteArray::number(start) + "-" +
                    QByteArray::number(start + body.size() - 1) + "/8\r\nConnection: close\r\n\r\n" + body);
                socket->disconnectFromHost();
            });
        });
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        const auto url = proxy.open({{"url", QString("http://127.0.0.1:%1/audio").arg(upstream.serverPort())}, {"contentLength", 8}});
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY(reply->isFinished());
        QCOMPARE(failures.count(), 0);
        QCOMPARE(reply->error(), QNetworkReply::NoError);
        QCOMPARE(reply->readAll(), QByteArray("abcdefgh"));
        QCOMPARE(requests.size(), 3);
        QVERIFY(requests[0].contains("Range: bytes=0-7"));
        QVERIFY(requests[1].contains("Range: bytes=3-7"));
        QVERIFY(requests[2].contains("Range: bytes=6-7"));
        reply->deleteLater();
    }

    void resumesInterruptedPartialResponse() {
        QTcpServer upstream;
        QVERIFY(upstream.listen(QHostAddress::LocalHost));
        QList<QByteArray> requests;
        connect(&upstream, &QTcpServer::newConnection, this, [&] {
            auto *socket = upstream.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [&, socket, request = QByteArray()]() mutable {
                request += socket->readAll();
                if (!request.endsWith("\r\n\r\n")) return;
                requests.append(request);
                if (requests.size() == 1)
                    socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: 8\r\nContent-Range: bytes 0-7/8\r\nConnection: close\r\n\r\nabc");
                else
                    socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: 5\r\nContent-Range: bytes 3-7/8\r\nConnection: close\r\n\r\ndefgh");
                socket->disconnectFromHost();
            });
        });
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        const auto url = proxy.open({{"url", QString("http://127.0.0.1:%1/audio").arg(upstream.serverPort())}, {"contentLength", 8}});
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY(reply->isFinished());
        QCOMPARE(failures.count(), 0);
        QCOMPARE(reply->error(), QNetworkReply::NoError);
        QCOMPARE(reply->readAll(), QByteArray("abcdefgh"));
        QCOMPARE(requests.size(), 2);
        QVERIFY(requests[1].contains("Range: bytes=3-7"));
        reply->deleteLater();
    }

    void suspendedStreamResumesWithoutSpendingRetryBudget() {
        QTcpServer upstream;
        QVERIFY(upstream.listen(QHostAddress::LocalHost));
        QList<QByteArray> requests;
        connect(&upstream, &QTcpServer::newConnection, this, [&] {
            auto *socket = upstream.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [&, socket, request = QByteArray()]() mutable {
                request += socket->readAll();
                if (!request.endsWith("\r\n\r\n")) return;
                requests.append(request);
                if (requests.size() == 1) {
                    socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: 8\r\nContent-Range: bytes 0-7/8\r\nConnection: close\r\n\r\nabc");
                    return;
                }
                socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: 5\r\nContent-Range: bytes 3-7/8\r\nConnection: close\r\n\r\ndefgh");
                socket->disconnectFromHost();
            });
        });
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        const auto url = proxy.open({{"url", QString("http://127.0.0.1:%1/audio").arg(upstream.serverPort())}, {"contentLength", 8}});
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY(reply->bytesAvailable() >= 3);
        QCOMPARE(reply->readAll(), QByteArray("abc"));

        proxy.setSuspended(true);
        QTest::qWait(150);
        QCOMPARE(failures.count(), 0);
        QCOMPARE(requests.size(), 1);

        proxy.setSuspended(false);
        QTRY_VERIFY(reply->isFinished());
        QCOMPARE(failures.count(), 0);
        QCOMPARE(reply->error(), QNetworkReply::NoError);
        QCOMPARE(reply->readAll(), QByteArray("defgh"));
        QCOMPARE(requests.size(), 2);
        QVERIFY(requests[1].contains("Range: bytes=3-7"));
        reply->deleteLater();
    }

    void validatesPartialResponseRanges_data() {
        QTest::addColumn<QByteArray>("range");
        QTest::addColumn<QByteArray>("length");
        QTest::addColumn<bool>("accepted");
        QTest::newRow("case and numeric formatting") << QByteArray("ByTeS 000-007/0008") << QByteArray("8") << true;
        QTest::newRow("unknown total") << QByteArray("bytes 0-7/*") << QByteArray("8") << true;
        QTest::newRow("wrong offset") << QByteArray("bytes 1-7/8") << QByteArray("7") << false;
        QTest::newRow("wrong total") << QByteArray("bytes 0-7/9") << QByteArray("8") << false;
        QTest::newRow("past request end") << QByteArray("bytes 0-8/8") << QByteArray("9") << false;
        QTest::newRow("missing range") << QByteArray() << QByteArray("8") << false;
        QTest::newRow("overflow") << QByteArray("bytes 0-999999999999999999999/8") << QByteArray("8") << false;
        QTest::newRow("inconsistent body length") << QByteArray("bytes 0-7/8") << QByteArray("7") << false;
    }

    void validatesPartialResponseRanges() {
        QFETCH(QByteArray, range);
        QFETCH(QByteArray, length);
        QFETCH(bool, accepted);
        QTcpServer upstream;
        QVERIFY(upstream.listen(QHostAddress::LocalHost));
        connect(&upstream, &QTcpServer::newConnection, this, [&] {
            auto *socket = upstream.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [&, socket, request = QByteArray()]() mutable {
                request += socket->readAll();
                if (!request.endsWith("\r\n\r\n")) return;
                socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: " + length +
                    "\r\nContent-Range: " + range + "\r\nConnection: close\r\n\r\nabcdefgh");
                socket->disconnectFromHost();
            });
        });
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        const auto url = proxy.open({{"url", QString("http://127.0.0.1:%1/audio").arg(upstream.serverPort())}, {"contentLength", 8}});
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY(reply->isFinished());
        if (accepted) {
            QCOMPARE(failures.count(), 0);
            QCOMPARE(reply->error(), QNetworkReply::NoError);
            QCOMPARE(reply->readAll(), QByteArray("abcdefgh"));
        } else {
            QCOMPARE(failures.count(), 1);
            QVERIFY(!failures.first().at(1).toString().isEmpty());
            QVERIFY(reply->readAll().isEmpty());
        }
        reply->deleteLater();
    }

    void interruptedRetriesAreBounded() {
        QTcpServer upstream;
        QVERIFY(upstream.listen(QHostAddress::LocalHost));
        int requests = 0;
        connect(&upstream, &QTcpServer::newConnection, this, [&] {
            auto *socket = upstream.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [&, socket, request = QByteArray()]() mutable {
                request += socket->readAll();
                if (!request.endsWith("\r\n\r\n")) return;
                ++requests;
                socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: 8\r\nContent-Range: bytes 0-7/8\r\nConnection: close\r\n\r\n");
                socket->disconnectFromHost();
            });
        });
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        const auto url = proxy.open({{"url", QString("http://127.0.0.1:%1/audio").arg(upstream.serverPort())}, {"contentLength", 8}});
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY(reply->isFinished());
        QCOMPARE(requests, 4);
        QCOMPARE(failures.count(), 1);
        QVERIFY(failures.first().at(1).toString().contains("interrupted"));
        reply->deleteLater();
    }

    void clearingStreamCancelsAnActiveDownload() {
        QTcpServer upstream;
        QVERIFY(upstream.listen(QHostAddress::LocalHost));
        QPointer<QTcpSocket> socket;
        connect(&upstream, &QTcpServer::newConnection, this, [&] {
            socket = upstream.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, this, [&] {
                socket->readAll();
                socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: 8\r\nContent-Range: bytes 0-7/8\r\n\r\na");
            });
        });
        AudioStreamProxy proxy;
        QSignalSpy failure(&proxy, &AudioStreamProxy::streamFailed);
        const auto url = proxy.open({{"url", QString("http://127.0.0.1:%1/audio").arg(upstream.serverPort())}, {"contentLength", 8}});
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY(reply->bytesAvailable() > 0);
        proxy.clear();
        QTRY_VERIFY(reply->isFinished());
        QTRY_VERIFY(socket && socket->state() == QAbstractSocket::UnconnectedState);
        QCOMPARE(failure.count(), 0);
        reply->deleteLater();
    }

    void supportsSeekingAndInvalidatesOldUrls() {
        QTcpServer upstream;
        QVERIFY(upstream.listen(QHostAddress::LocalHost));
        QByteArray requested;
        connect(&upstream, &QTcpServer::newConnection, this, [&] {
            auto *socket = upstream.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [&, socket] {
                requested += socket->readAll();
                if (!requested.endsWith("\r\n\r\n")) return;
                socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\nContent-Range: bytes 5-7/8\r\nConnection: close\r\n\r\nfgh");
                socket->disconnectFromHost();
            });
        });
        AudioStreamProxy proxy;
        QJsonObject stream{{"url", QString("http://127.0.0.1:%1/audio").arg(upstream.serverPort())}, {"contentLength", 8}};
        const auto oldUrl = proxy.open(stream);
        const auto url = proxy.open(stream);
        QNetworkAccessManager client;
        auto *old = client.get(QNetworkRequest(oldUrl));
        QTRY_VERIFY(old->isFinished());
        QCOMPARE(old->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt(), 404);
        QNetworkRequest request(url);
        request.setRawHeader("Range", "bytes=5-");
        auto *reply = client.get(request);
        QTRY_VERIFY(reply->isFinished());
        QCOMPARE(reply->readAll(), QByteArray("fgh"));
        QCOMPARE(reply->rawHeader("Content-Range"), QByteArray("bytes 5-7/8"));
        QVERIFY(requested.contains("Range: bytes=5-7"));
        old->deleteLater(); reply->deleteLater();
    }

    void servesProviderStreamsThroughTheRangeReader() {
        // Three provider chunks plus a tail, so the reader is asked more than once.
        QByteArray flac(3 * 512 * 1024 + 100, Qt::Uninitialized);
        for (qsizetype i = 0; i < flac.size(); ++i) flac[i] = char(i * 7);
        QList<QPair<qint64, qint64>> reads;
        AudioStreamProxy proxy;
        proxy.setRangeReader([&](const QJsonObject &stream, qint64 start, qint64 end, auto done) {
            QCOMPARE(stream.value("playbackId").toString(), QString("abc"));
            reads.append({start, end});
            // Answer later, as the provider thread would.
            QTimer::singleShot(0, [&flac, start, end, done] { done(flac.mid(start, end - start + 1), 0); });
        });
        const auto url = proxy.open({{"provider", "qobuz"}, {"playbackId", "abc"},
                                     {"contentLength", double(flac.size())}, {"mimeType", "audio/flac"}});
        QNetworkAccessManager client;
        QNetworkRequest request(url);
        request.setRawHeader("Range", "bytes=10-");
        auto *reply = client.get(request);
        QTRY_VERIFY(reply->isFinished());
        QCOMPARE(reply->error(), QNetworkReply::NoError);
        QCOMPARE(reply->header(QNetworkRequest::ContentTypeHeader).toByteArray(), QByteArray("audio/flac"));
        QCOMPARE(reply->readAll(), flac.mid(10));
        QCOMPARE(reads.first(), qMakePair(qint64(10), qint64(10 + 512 * 1024 - 1)));
        QCOMPARE(reads.last().second, qint64(flac.size() - 1));
        reply->deleteLater();
    }

    void reportsProviderReadFailures() {
        AudioStreamProxy proxy;
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        proxy.setRangeReader([](const QJsonObject &, qint64, qint64, auto done) { done({}, 502); });
        const auto url = proxy.open({{"provider", "qobuz"}, {"playbackId", "gone"}, {"contentLength", 64}});
        QNetworkAccessManager client;
        auto *reply = client.get(QNetworkRequest(url));
        QTRY_VERIFY(reply->isFinished());
        QCOMPARE(failures.count(), 1);
        QCOMPARE(failures.first().at(0).toInt(), 502);
        reply->deleteLater();
    }
};
QTEST_GUILESS_MAIN(AudioStreamProxyTest)
#include "audio_stream_proxy_test.moc"
