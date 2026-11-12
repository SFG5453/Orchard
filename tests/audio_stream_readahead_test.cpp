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
#include <QRegularExpression>
#include <QSignalSpy>
#include <QTcpSocket>
#include <QTimer>
#include <QtTest>

namespace {
constexpr qint64 kMiB = 1024 * 1024;
constexpr int kSmallBuffer = 16 * 1024;

QByteArray audioBytes(qint64 size) {
    QByteArray body(size, Qt::Uninitialized);
    for (qsizetype index = 0; index < body.size(); ++index) body[index] = char(index % 251);
    return body;
}

// Ranged upstream that resets a response still unsent after `patienceMs`, as the CDN does to slow readers.
class Upstream final : public QObject {
public:
    explicit Upstream(QByteArray body, int patienceMs = 0) : m_body(std::move(body)), m_patienceMs(patienceMs) {
        QVERIFY(m_server.listen(QHostAddress::LocalHost));
        connect(&m_server, &QTcpServer::newConnection, this, [this] {
            auto *socket = m_server.nextPendingConnection();
            // A small send buffer keeps unread bytes visible in bytesToWrite().
            socket->setSocketOption(QAbstractSocket::SendBufferSizeSocketOption, kSmallBuffer);
            connect(socket, &QTcpSocket::readyRead, socket, [this, socket, request = QByteArray()]() mutable {
                request += socket->readAll();
                if (request.endsWith("\r\n\r\n")) respond(socket, request);
            });
        });
    }
    QJsonObject stream() const {
        return {{"url", QString("http://127.0.0.1:%1/audio").arg(m_server.serverPort())},
                {"contentLength", m_body.size()}};
    }
    QList<qint64> offsets;
    int resets{0};
private:
    void respond(QTcpSocket *socket, const QByteArray &request) {
        const auto match = QRegularExpression("Range: bytes=(\\d+)-(\\d+)").match(QString::fromLatin1(request));
        const qint64 start = match.captured(1).toLongLong(), end = match.captured(2).toLongLong();
        offsets.append(start);
        socket->write("HTTP/1.1 206 Partial Content\r\nContent-Length: " + QByteArray::number(end - start + 1) +
                      "\r\nContent-Range: bytes " + QByteArray::number(start) + "-" + QByteArray::number(end) +
                      "/" + QByteArray::number(m_body.size()) + "\r\nConnection: close\r\n\r\n" +
                      m_body.mid(start, end - start + 1));
        socket->disconnectFromHost();
        if (m_patienceMs <= 0) return;
        QTimer::singleShot(m_patienceMs, socket, [this, socket] {
            if (socket->bytesToWrite() == 0) return;
            ++resets;
            socket->abort();
        });
    }
    QTcpServer m_server;
    QByteArray m_body;
    int m_patienceMs;
};

// Stands in for the decoder: reads only when told to, through buffers too small to hide a stalled proxy.
class Player final {
public:
    void open(AudioStreamProxy &proxy, const QUrl &url) {
        QVERIFY(m_socket.bind(QHostAddress::LocalHost));
        m_socket.setSocketOption(QAbstractSocket::ReceiveBufferSizeSocketOption, kSmallBuffer);
        m_socket.setReadBufferSize(kSmallBuffer);
        m_socket.connectToHost(url.host(), url.port());
        QVERIFY(m_socket.waitForConnected());
        QTRY_VERIFY(!proxy.findChildren<QTcpSocket *>().isEmpty());
        for (auto *accepted : proxy.findChildren<QTcpSocket *>())
            accepted->setSocketOption(QAbstractSocket::SendBufferSizeSocketOption, kSmallBuffer);
        m_socket.write("GET " + url.path().toLatin1() + " HTTP/1.1\r\nHost: player\r\n\r\n");
    }
    void read(qint64 limit) {
        m_received += m_socket.read(limit);
    }
    // Drains the stream at full speed until the proxy closes it.
    QByteArray finish() {
        m_socket.setReadBufferSize(0);
        QElapsedTimer timer;
        timer.start();
        while (m_socket.state() != QAbstractSocket::UnconnectedState && timer.elapsed() < 20000) {
            m_received += m_socket.readAll();
            QTest::qWait(5);
        }
        m_received += m_socket.readAll();
        return m_received.mid(m_received.indexOf("\r\n\r\n") + 4);
    }
private:
    QTcpSocket m_socket;
    QByteArray m_received;
};
}

class AudioStreamReadAheadTest : public QObject {
    Q_OBJECT
private slots:
    void fetchesAheadOfAPlayerThatIsNotReading() {
        const auto body = audioBytes(6 * kMiB);
        Upstream upstream(body);
        AudioStreamProxy proxy;
        proxy.setReadAhead(2 * kMiB);
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        Player player;
        player.open(proxy, proxy.open(upstream.stream()));

        // A third chunk is taken while fewer than two are queued.
        const QList<qint64> ahead{0, kMiB, 2 * kMiB};
        QTRY_COMPARE(upstream.offsets, ahead);
        QTest::qWait(200);
        QCOMPARE(upstream.offsets, ahead);

        QCOMPARE(player.finish(), body);
        QCOMPARE(upstream.offsets, (QList<qint64>{0, kMiB, 2 * kMiB, 3 * kMiB, 4 * kMiB, 5 * kMiB}));
        QCOMPARE(failures.count(), 0);
    }

    void slowPlayerNeverLeavesAnUpstreamResponseUnread() {
        const auto body = audioBytes(3 * kMiB);
        Upstream upstream(body, 400);
        AudioStreamProxy proxy;
        proxy.setReadAhead(2 * kMiB);
        QSignalSpy failures(&proxy, &AudioStreamProxy::streamFailed);
        Player player;
        player.open(proxy, proxy.open(upstream.stream()));

        // Playback-rate reads last well past the upstream's patience.
        for (int tick = 0; tick < 60; ++tick) {
            player.read(4096);
            QTest::qWait(20);
        }
        QCOMPARE(upstream.resets, 0);
        QCOMPARE(player.finish(), body);
        QCOMPARE(upstream.offsets, (QList<qint64>{0, kMiB, 2 * kMiB}));
        QCOMPARE(failures.count(), 0);
    }
};

QTEST_MAIN(AudioStreamReadAheadTest)
#include "audio_stream_readahead_test.moc"
