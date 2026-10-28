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

#include "appearance/artwork_video_cache.h"

#include <QDir>
#include <QFile>
#include <QSignalSpy>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QTest>
#include <QUrl>

class ArtworkVideoCacheTest : public QObject {
  Q_OBJECT

private slots:
  void passesLocalUrlsThrough() {
    QTemporaryDir dir;
    ArtworkVideoCache cache(dir.path());
    const QString local = QStringLiteral("file:///tmp/loop.mp4");
    QCOMPARE(cache.localUrl(local), local);
  }

  void downloadsOnceThenServesTheFile() {
    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost));
    int requests = 0;
    const QByteArray body(4096, 'v');
    connect(&server, &QTcpServer::newConnection, this, [&] {
      QTcpSocket *socket = server.nextPendingConnection();
      ++requests;
      connect(socket, &QTcpSocket::readyRead, socket, [socket, body] {
        socket->readAll();
        socket->write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nConnection: close\r\n"
                      "Content-Length: " + QByteArray::number(body.size()) + "\r\n\r\n" + body);
        socket->disconnectFromHost();
      });
    });
    const QString remote =
        QStringLiteral("http://127.0.0.1:%1/loop.mp4").arg(server.serverPort());

    QTemporaryDir dir;
    ArtworkVideoCache cache(dir.path());
    QSignalSpy ready(&cache, &ArtworkVideoCache::ready);
    QVERIFY(cache.localUrl(remote).isEmpty());
    QVERIFY(cache.localUrl(remote).isEmpty());
    QVERIFY(ready.wait(5000));
    QCOMPARE(requests, 1);

    const QString local = ready.at(0).at(1).toString();
    QFile file(QUrl(local).toLocalFile());
    QVERIFY(file.open(QIODevice::ReadOnly));
    QCOMPARE(file.readAll(), body);
    QCOMPARE(cache.localUrl(remote), local);
    file.close(); // Windows cannot delete a file that is still open.

    cache.discard(remote);
    QVERIFY(!QFile::exists(QUrl(local).toLocalFile()));
  }

  void reportsMissingFilesAsPermanent() {
    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost));
    connect(&server, &QTcpServer::newConnection, this, [&] {
      QTcpSocket *socket = server.nextPendingConnection();
      connect(socket, &QTcpSocket::readyRead, socket, [socket] {
        socket->readAll();
        socket->write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        socket->disconnectFromHost();
      });
    });
    QTemporaryDir dir;
    ArtworkVideoCache cache(dir.path());
    QSignalSpy failed(&cache, &ArtworkVideoCache::failed);
    QVERIFY(cache.localUrl(QStringLiteral("http://127.0.0.1:%1/gone.mp4")
                               .arg(server.serverPort()))
                .isEmpty());
    QVERIFY(failed.wait(5000));
    QCOMPARE(failed.at(0).at(1).toBool(), true);
    // Windows QSaveFile keeps a named temp file until the reply lets go of it.
    QTRY_COMPARE(QDir(dir.path()).entryList(QDir::Files).size(), 0);
  }
};

QTEST_GUILESS_MAIN(ArtworkVideoCacheTest)
#include "artwork_video_cache_test.moc"
